<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ReadBankStatementTool;
use Maggie\Finance\Tests\Bank\BankStatementTestTrait;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\Response\MockResponse;

class ReadBankStatementToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use BankStatementTestTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->makeKey();
        $this->loadFixtures(__DIR__.'/../Bank/fixtures/account.yaml', __DIR__.'/../Bank/fixtures/other_user_account.yaml');
        $this->connectAccount();
    }

    protected function tearDown(): void
    {
        $this->dropKey();
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function read(array $arguments, ?callable $answer = null): array
    {
        $tool = new ReadBankStatementTool(
            $this->readerOn($answer ?? fn () => $this->pageOf($this->september())),
            self::getContainer()->get(ImportStatement::class),
            self::getContainer()->get(McpUserContext::class),
        );

        $arguments += ['accountId' => (string) $this->getFixture('checking')->getId(), 'from' => '2026-09-01', 'to' => '2026-09-30'];

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function storedCount(): int
    {
        return self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Transaction::class)->count([]);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->read([]));
    }

    public function testBadInputsAreErrors(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->read(['from' => '2026-9-1']));
        self::assertArrayHasKey('error', $this->read(['from' => '2026-10-01', 'to' => '2026-09-01']));
        self::assertArrayHasKey('error', $this->read(['direction' => 'sideways']));
        self::assertArrayHasKey('error', $this->read(['accountId' => 'nope']));
        self::assertArrayHasKey('error', $this->read(['accountId' => (string) $this->getFixture('other_account')->getId()]));
        self::assertSame([], $this->requested);
    }

    public function testSearchFiltersLabelAndCounterparty(): void
    {
        $this->loginFixtureUser();

        $result = $this->read(['search' => 'n26']);

        self::assertSame('ok', $result['status']);
        self::assertSame(2, $result['count']);
        self::assertSame(['ref-n26-1', 'ref-n26-2'], array_column($result['entries'], 'ref'));
        self::assertSame('2026-09-02', $result['firstDay']);
        self::assertSame('2026-09-09', $result['lastDay']);
        self::assertFalse($result['truncated']);
        self::assertStringContainsString('date_from=2026-09-01', $this->requested[0]);
        self::assertStringContainsString('date_to=2026-09-30', $this->requested[0]);
        self::assertSame(5000, $result['entries'][0]['amountCents']);
    }

    public function testSearchIgnoresAccentsAndCase(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['ref-n26-1'], array_column($this->read(['search' => 'LEO'])['entries'], 'ref'));
    }

    public function testDirectionAndMinAmountNarrowTheAnswer(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['ref-shop-1', 'ref-shop-2'], array_column($this->read(['direction' => 'debit'])['entries'], 'ref'));
        self::assertSame(['ref-salary'], array_column($this->read(['minAmountCents' => 100000])['entries'], 'ref'));
    }

    public function testInMaggieMarksKnownMovements(): void
    {
        $this->loginFixtureUser();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $known = (new Transaction())
            ->setUser($this->getFixture('test_user'))
            ->setAccount($this->getFixture('checking'))
            ->setLabel('Virement N26 Léo')
            ->setAmountCents(5000)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-09-02'))
            ->setExternalId('ref-n26-1');
        $em->persist($known);
        $em->flush();

        $entries = $this->read(['search' => 'n26'])['entries'];

        self::assertSame([true, false], array_column($entries, 'inMaggie'));
    }

    public function testNothingIsWritten(): void
    {
        $this->loginFixtureUser();

        $this->read([]);

        self::assertSame(0, $this->storedCount());
    }

    public function testARefusedPeriodIsHistoryUnavailableAndNothingElse(): void
    {
        $this->loginFixtureUser();

        $result = $this->read([], fn () => new MockResponse(json_encode(['message' => 'too old'], JSON_THROW_ON_ERROR), ['http_code' => 422]));

        self::assertSame(['status' => 'history_unavailable', 'message' => 'too old'], $result);
        self::assertSame(0, $this->storedCount());
    }

    public function testAReachedCeilingIsRateLimited(): void
    {
        $this->loginFixtureUser();

        $result = $this->read([], fn () => new MockResponse('{}', ['http_code' => 429]));

        self::assertSame('rate_limited', $result['status']);
        self::assertSame(0, $this->storedCount());
    }

    public function testALongAnswerIsTruncatedAtTwoHundredEntries(): void
    {
        $this->loginFixtureUser();
        $many = [];
        for ($i = 0; $i < 210; ++$i) {
            $many[] = $this->movement('2026-09-10', '1.00', 'DBIT', 'SHOP '.$i, 'ref-'.$i);
        }

        $result = $this->read([], fn () => $this->pageOf($many));

        self::assertCount(200, $result['entries']);
        self::assertSame(210, $result['count']);
        self::assertTrue($result['truncated']);
    }
}
