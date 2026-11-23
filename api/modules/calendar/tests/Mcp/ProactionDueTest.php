<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\ProactionOutcome;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionKind;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Mcp\Tool\ClaimDueProactionsTool;
use Maggie\Calendar\Mcp\Tool\RecordProactionResultTool;
use PHPUnit\Framework\Attributes\DataProvider;
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

    public function testAProactionNamedByItsIdIsClaimedNowEvenBeforeItsTime(): void
    {
        // The manual trigger (MAG-249) runs one now, through the same claim (MAG-327).
        $data = $this->call(ClaimDueProactionsTool::class, null, $this->id('future'));

        self::assertSame([$this->id('future')], array_column($data['proactions'], 'id'));
        self::assertSame(ProactionStatus::Running, $this->stored('future')->getExecutionStatus());
        self::assertSame(ProactionStatus::Pending, $this->stored('due_first')->getExecutionStatus());

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatchedFor(Proaction::class, $this->id('future'));
    }

    public function testAProactionNamedByItsIdIsClaimedOnlyWhilePendingAndOnlyByItsUser(): void
    {
        self::assertSame(0, $this->call(ClaimDueProactionsTool::class, null, $this->id('already_running'))['count']);
        self::assertSame(0, $this->call(ClaimDueProactionsTool::class, null, 'not-an-id')['count']);

        $this->loginFixtureUser('other_user');
        self::assertSame(0, $this->call(ClaimDueProactionsTool::class, null, $this->id('future'))['count']);
        self::assertSame(ProactionStatus::Pending, $this->stored('future')->getExecutionStatus());
    }

    public function testTheClaimHonoursItsLimit(): void
    {
        $data = $this->call(ClaimDueProactionsTool::class, 1);

        self::assertSame([$this->id('due_first')], array_column($data['proactions'], 'id'));
        self::assertSame(ProactionStatus::Pending, $this->stored('due_second')->getExecutionStatus());
    }

    public function testRecordingACompletedRunStoresTheResponseAndPublishes(): void
    {
        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Pense à déposer Tom !');

        self::assertTrue($data['success']);
        $stored = $this->stored('already_running');
        self::assertSame(ProactionStatus::Completed, $stored->getExecutionStatus());
        self::assertSame('Pense à déposer Tom !', $stored->getResponse());
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

    public function testACompletedRunKeepsItsDeliveryTopicAndVerdict(): void
    {
        // The trace the next check reads back (MAG-886).
        $data = $this->call(
            RecordProactionResultTool::class,
            $this->id('already_running'),
            'completed',
            'Votre enveloppe Loisirs est à 72 %, monsieur.',
            delivery: 'thread',
            topic_key: 'enveloppe-loisirs',
            impact: 'low',
            evidence: 'solid',
            deadline: '2037-01-15T08:30:00+01:00',
            changed: 'Passée de 60 % à 72 %.',
            value: '72 %',
            notice: 'Votre enveloppe Loisirs est à 72 %, monsieur.',
            message_link: '/chat?message=01JABCDEF',
        );

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame('thread', $data['proaction']['delivery']);
        self::assertSame('enveloppe-loisirs', $data['proaction']['topicKey']);

        $stored = $this->stored('already_running');
        self::assertSame(ProactionStatus::Completed, $stored->getExecutionStatus());
        self::assertSame(ProactionDelivery::Thread, $stored->getDelivery());
        self::assertSame('enveloppe-loisirs', $stored->getTopicKey());
        self::assertSame(['impact' => 'low', 'evidence' => 'solid', 'deadline' => '2037-01-15T08:30:00+01:00', 'changed' => 'Passée de 60 % à 72 %.', 'value' => '72 %'], $stored->getVerdict());
        self::assertSame('Votre enveloppe Loisirs est à 72 %, monsieur.', $stored->getNotice());
        self::assertSame('/chat?message=01JABCDEF', $stored->getMessageLink());

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatchedFor(Proaction::class, $this->id('already_running'));
    }

    public function testALongValueIsCutTo120Characters(): void
    {
        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Réponse', topic_key: 'sujet', value: str_repeat('é', 200));

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame(str_repeat('é', 120), $this->stored('already_running')->getVerdict()['value']);
    }

    /** @return iterable<string, array{array<string, string>}> */
    public static function badOutcomes(): iterable
    {
        yield 'delivery' => [['delivery' => 'push']];
        yield 'impact' => [['impact' => 'urgent']];
        yield 'evidence' => [['evidence' => 'strong']];
        yield 'deadline' => [['deadline' => 'demain']];
        yield 'impossible deadline' => [['deadline' => '2037-02-30T08:30:00']];
        yield 'topic' => [['topic_key' => 'Enveloppe Loisirs']];
        yield 'long topic' => [['topic_key' => str_repeat('a', 81)]];
        yield 'message link' => [['message_link' => 'https://evil.example/chat?message=1']];
    }

    /** @param array<string, string> $outcome */
    #[DataProvider('badOutcomes')]
    public function testAnInvalidOutcomeIsRefusedAndTheProactionStaysRunning(array $outcome): void
    {
        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Réponse', ...$outcome);

        self::assertArrayHasKey('error', $data);
        self::assertSame(ProactionStatus::Running, $this->stored('already_running')->getExecutionStatus());
        $this->assertMercureUpdateCount(0);
    }

    public function testACheckIsClaimedWithTheTopicsItsUserWasToldAboutThisWeek(): void
    {
        $check = $this->proaction('maggie_agenda', 'Vérifie l\'enveloppe Loisirs', ProactionKind::Check);
        $this->told('maggie_agenda', 'enveloppe-loisirs', ProactionDelivery::Thread, '-2 days', 'Loisirs à 60 %.');
        $this->told('maggie_agenda', 'enveloppe-loisirs', ProactionDelivery::Interrupt, '-1 day', 'Loisirs à 72 %.');
        $this->told('maggie_agenda', 'facture-edf', ProactionDelivery::Interrupt, '-3 days', 'Facture EDF due.');
        // Said nothing, too old, or another user's: never a topic told.
        $this->told('maggie_agenda', 'detail', ProactionDelivery::None, '-1 day', null);
        $this->told('maggie_agenda', 'ancien', ProactionDelivery::Thread, '-8 days', 'Ancien.');
        $this->told('other_maggie_agenda', 'chien', ProactionDelivery::Interrupt, '-1 day', 'Le chien.');

        $data = $this->call(ClaimDueProactionsTool::class, null, $check);

        self::assertSame('check', $data['proactions'][0]['kind']);
        $topics = $data['proactions'][0]['signaledTopics'];
        self::assertSame(['enveloppe-loisirs', 'facture-edf'], array_column($topics, 'topic'));
        self::assertSame('interrupt', $topics[0]['delivery']);
        self::assertSame('Loisirs à 72 %.', $topics[0]['notice']);
        self::assertNotEmpty($topics[0]['at']);
    }

    public function testACheckIsClaimedWithTheLastValuePerTopicItVerified(): void
    {
        $check = $this->proaction('maggie_agenda', 'Vérifie l\'enveloppe Loisirs', ProactionKind::Check);
        $this->told('maggie_agenda', 'enveloppe-loisirs', ProactionDelivery::Thread, '-2 days', 'Loisirs à 60 %.', '60 %');
        $this->told('maggie_agenda', 'enveloppe-loisirs', ProactionDelivery::None, '-1 day', null, '72 %');
        $this->told('maggie_agenda', 'colis', ProactionDelivery::None, '-10 days', null, 'en transit');
        // No value, too old, or another user's: never a checked value.
        $this->told('maggie_agenda', 'sans-valeur', ProactionDelivery::None, '-1 day', null);
        $this->told('maggie_agenda', 'ancien', ProactionDelivery::None, '-41 days', null, 'vieux');
        $this->told('other_maggie_agenda', 'chien', ProactionDelivery::None, '-1 day', null, 'dort');

        $data = $this->call(ClaimDueProactionsTool::class, null, $check);

        $values = $data['proactions'][0]['checkedValues'];
        self::assertSame(['enveloppe-loisirs', 'colis'], array_column($values, 'topic'));
        self::assertSame(['72 %', 'en transit'], array_column($values, 'value'));
        self::assertNotEmpty($values[0]['at']);
    }

    public function testAPassWithoutValueKeepsThePreviousOne(): void
    {
        $check = $this->proaction('maggie_agenda', 'Vérifie le colis', ProactionKind::Check);
        $this->told('maggie_agenda', 'colis', ProactionDelivery::None, '-3 days', null, 'en transit');
        $this->told('maggie_agenda', 'colis', ProactionDelivery::None, '-1 day', null);

        $data = $this->call(ClaimDueProactionsTool::class, null, $check);

        self::assertSame(['en transit'], array_column($data['proactions'][0]['checkedValues'], 'value'));
    }

    public function testAReminderIsClaimedWithoutSignaledTopics(): void
    {
        $this->told('maggie_agenda', 'enveloppe-loisirs', ProactionDelivery::Interrupt, '-1 day', 'Loisirs à 72 %.');

        $data = $this->call(ClaimDueProactionsTool::class, null, $this->id('future'));

        self::assertSame('reminder', $data['proactions'][0]['kind']);
        self::assertArrayNotHasKey('signaledTopics', $data['proactions'][0]);
        self::assertArrayNotHasKey('checkedValues', $data['proactions'][0]);
    }

    /** A pending proaction due now, its id. */
    private function proaction(string $agenda, string $prompt, ProactionKind $kind): string
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $proaction = (new Proaction())->setPrompt($prompt)->setKind($kind);
        $proaction->setSummary(Proaction::summaryOf($prompt));
        $proaction->scheduleAt(new \DateTimeImmutable('-1 minute'));
        $em->find(Agenda::class, $this->getFixture($agenda)->getId())->addEvent($proaction);
        $em->persist($proaction);
        $em->flush();

        return (string) $proaction->getId();
    }

    private function told(string $agenda, string $topic, ProactionDelivery $delivery, string $ago, ?string $notice, ?string $value = null): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $proaction = (new Proaction())->setPrompt('Vérifie '.$topic)->setKind(ProactionKind::Check);
        $proaction->setSummary('Vérifie '.$topic);
        $proaction->scheduleAt(new \DateTimeImmutable($ago));
        $em->find(Agenda::class, $this->getFixture($agenda)->getId())->addEvent($proaction);
        $proaction->markRunning()->markCompleted(new ProactionOutcome(response: $notice, delivery: $delivery, topicKey: $topic, verdict: null !== $value ? ['value' => $value] : null, notice: $notice), new \DateTimeImmutable($ago));
        $em->persist($proaction);
        $em->flush();
    }

    public function testAUserCannotRecordTheResultOfAnotherUsersProaction(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->call(RecordProactionResultTool::class, $this->id('already_running'), 'completed', 'Pas à moi');

        self::assertStringContainsString('not found', $data['error'] ?? '');
        self::assertSame(ProactionStatus::Running, $this->stored('already_running')->getExecutionStatus());
    }
}
