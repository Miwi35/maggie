<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ImportBankTransactionsTool;
use Maggie\Finance\Tests\Bank\BankStatementTestTrait;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\Response\MockResponse;

class ImportBankTransactionsToolTest extends KernelTestCase
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
    private function import(array $arguments, ?callable $answer = null): array
    {
        $tool = new ImportBankTransactionsTool(
            $this->readerOn($answer ?? fn () => $this->pageOf($this->september())),
            self::getContainer()->get(ImportStatement::class),
            self::getContainer()->get(McpUserContext::class),
        );

        $arguments += ['accountId' => (string) $this->getFixture('checking')->getId(), 'from' => '2026-09-01', 'to' => '2026-09-30'];

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<string> */
    private function storedReferences(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $references = array_map(static fn (Transaction $t) => $t->getExternalId(), $em->getRepository(Transaction::class)->findAll());
        sort($references);

        return $references;
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->import(['refs' => ['ref-n26-1']]));
    }

    public function testBadInputsAreErrorsAndNothingIsWritten(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->import([]));
        self::assertArrayHasKey('error', $this->import(['refs' => []]));
        self::assertArrayHasKey('error', $this->import(['refs' => ['ref-n26-1'], 'from' => 'yesterday']));
        self::assertArrayHasKey('error', $this->import(['refs' => ['ref-n26-1'], 'from' => '2026-10-01']));
        self::assertArrayHasKey('error', $this->import(['refs' => ['ref-n26-1'], 'accountId' => (string) $this->getFixture('other_account')->getId()]));
        self::assertSame([], $this->requested);
        self::assertSame([], $this->storedReferences());
    }

    public function testImportsOnlyTheChosenRefs(): void
    {
        $this->loginFixtureUser();

        $result = $this->import(['refs' => ['ref-n26-1', 'ref-n26-2']]);

        self::assertSame(2, $result['imported']);
        self::assertSame(0, $result['skipped']);
        self::assertSame([], $result['unknownRefs']);
        self::assertCount(2, $result['transactionIds']);
        self::assertSame(['ref-n26-1', 'ref-n26-2'], $this->storedReferences());
        self::assertStringContainsString('date_from=2026-09-01', $this->requested[0]);
        self::assertStringContainsString('date_to=2026-09-30', $this->requested[0]);
    }

    public function testASecondImportSkipsThem(): void
    {
        $this->loginFixtureUser();
        $this->import(['refs' => ['ref-n26-1', 'ref-n26-2']]);

        $again = $this->import(['refs' => ['ref-n26-1', 'ref-n26-2']]);

        self::assertSame(0, $again['imported']);
        self::assertSame(2, $again['skipped']);
        self::assertSame(['ref-n26-1', 'ref-n26-2'], $this->storedReferences());
    }

    public function testUnknownRefsAreReturned(): void
    {
        $this->loginFixtureUser();

        $result = $this->import(['refs' => ['ref-n26-1', 'ref-nowhere']]);

        self::assertSame(1, $result['imported']);
        self::assertSame(['ref-nowhere'], $result['unknownRefs']);
    }

    public function testARefusedPeriodOrACeilingImportsNothing(): void
    {
        $this->loginFixtureUser();

        $refused = $this->import(['refs' => ['ref-n26-1']], fn () => new MockResponse(json_encode(['message' => 'too old'], JSON_THROW_ON_ERROR), ['http_code' => 422]));
        $limited = $this->import(['refs' => ['ref-n26-1']], fn () => new MockResponse('{}', ['http_code' => 429]));

        self::assertSame('history_unavailable', $refused['status']);
        self::assertSame('rate_limited', $limited['status']);
        self::assertSame([], $this->storedReferences());
    }
}
