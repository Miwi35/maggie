<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Mcp\Tool\ClaimDueProactionsTool;
use Maggie\Calendar\Mcp\Tool\RecordProactionResultTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** claim_due_proactions and record_proaction_result, the executor's tools (MAG-326). */
class ProactionDueTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private ?Connection $rival = null;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ProactionDueTest.yaml');
    }

    protected function tearDown(): void
    {
        if (null !== $this->rival) {
            if ($this->rival->isTransactionActive()) {
                $this->rival->rollBack();
            }
            $this->rival->close();
            $this->rival = null;
        }
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$args): array
    {
        return json_decode(self::getContainer()->get($tool)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function stored(string $ref): Proaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Proaction::class, $this->getFixture($ref)->getId());
    }

    public function testTheServiceClaimsTheDueProactionsOfEveryUserOldestFirstAndMarksThemRunning(): void
    {
        // No user bound: the service token alone.
        $data = $this->call(ClaimDueProactionsTool::class);

        self::assertSame([$this->id('due_first'), $this->id('other_due'), $this->id('due_second')], array_column($data['proactions'], 'id'));
        self::assertSame([(string) $this->getFixture('test_user')->getId(), (string) $this->getFixture('other_user')->getId(), (string) $this->getFixture('test_user')->getId()], array_column($data['proactions'], 'userId'));
        self::assertSame(['running', 'running', 'running'], array_column($data['proactions'], 'status'));

        foreach (['due_first', 'due_second', 'other_due'] as $ref) {
            self::assertSame(ProactionStatus::Running, $this->stored($ref)->getExecutionStatus(), $ref);
        }
        self::assertSame(ProactionStatus::Pending, $this->stored('future')->getExecutionStatus());

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    public function testAClaimNeverReturnsTheSameProactionTwice(): void
    {
        $first = $this->call(ClaimDueProactionsTool::class);
        $second = $this->call(ClaimDueProactionsTool::class);

        self::assertSame(3, $first['count']);
        self::assertSame(0, $second['count']);
    }

    public function testTwoSimultaneousClaimsNeverShareAProaction(): void
    {
        // A second executor, on its own connection, is in the middle of claiming
        // `due_first`: it holds the row, and has not committed yet.
        $connection = self::getContainer()->get('doctrine.orm.entity_manager')->getConnection();
        $this->rival = DriverManager::getConnection($connection->getParams(), $connection->getConfiguration());
        $this->rival->beginTransaction();
        $dueFirst = $this->getFixture('due_first')->getId()->toRfc4122();
        self::assertSame(1, count($this->rival->fetchFirstColumn('SELECT id FROM proaction WHERE id = ? FOR UPDATE', [$dueFirst])));

        // This one does not wait for it, and does not take it.
        $mine = $this->call(ClaimDueProactionsTool::class);
        self::assertSame([$this->id('other_due'), $this->id('due_second')], array_column($mine['proactions'], 'id'));

        // The rival marks it running and commits: nothing is left for anyone.
        $this->rival->executeStatement("UPDATE proaction SET execution_status = 'running' WHERE id = ?", [$dueFirst]);
        $this->rival->commit();

        self::assertSame(0, $this->call(ClaimDueProactionsTool::class)['count']);
    }

    public function testAUserOnlyEverClaimsTheirOwn(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ClaimDueProactionsTool::class);

        self::assertSame([$this->id('due_first'), $this->id('due_second')], array_column($data['proactions'], 'id'));
        self::assertSame(ProactionStatus::Pending, $this->stored('other_due')->getExecutionStatus());
    }

    public function testTheClaimHonoursItsLimit(): void
    {
        $data = $this->call(ClaimDueProactionsTool::class, 1);

        self::assertSame([$this->id('due_first')], array_column($data['proactions'], 'id'));
        self::assertSame(ProactionStatus::Pending, $this->stored('due_second')->getExecutionStatus());
    }

    public function testRecordingACompletedRunStoresTheResponseAndPublishes(): void
    {
        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Pense à déposer Ael !');

        self::assertTrue($data['success']);
        $stored = $this->stored('already_running');
        self::assertSame(ProactionStatus::Completed, $stored->getExecutionStatus());
        self::assertSame('Pense à déposer Ael !', $stored->getResponse());
        self::assertNotNull($stored->getCompletedAt());

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatchedFor(Proaction::class, $this->id('already_running'));
    }

    public function testRecordingAFailedRunStoresTheError(): void
    {
        $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'failed', null, 'LLM timeout');

        $stored = $this->stored('already_running');
        self::assertSame(ProactionStatus::Failed, $stored->getExecutionStatus());
        self::assertSame('LLM timeout', $stored->getError());
    }

    public function testOnlyARunningProactionTakesAResult(): void
    {
        $data = $this->call(RecordProactionResultTool::class, $this->id('future'), 'completed', 'Trop tôt');

        self::assertStringContainsString('not running', $data['error'] ?? '');
        self::assertSame(ProactionStatus::Pending, $this->stored('future')->getExecutionStatus());
    }

    public function testAResultIsCompletedOrFailed(): void
    {
        foreach (['pending', 'running', 'done'] as $status) {
            $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), $status);
            self::assertArrayHasKey('error', $data, $status);
        }
        self::assertSame(ProactionStatus::Running, $this->stored('already_running')->getExecutionStatus());
    }

    public function testAUserCannotRecordTheResultOfAnotherUsersProaction(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Pas à moi');

        self::assertStringContainsString('not found', $data['error'] ?? '');
        self::assertSame(ProactionStatus::Running, $this->stored('already_running')->getExecutionStatus());
    }
}
