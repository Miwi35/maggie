<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The savings account a single-legged advance comes from, named by hand
 * through manage_transactions (MAG-806): a livret no bank sync gives a debit leg.
 */
class AdvanceSourceToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use SavingsAdvancesWorld;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/savings_advances.yaml';

    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(self::FIXTURES);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function update(string $transactionId, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        return json_decode($tool(...['action' => 'update', 'transactionId' => $transactionId, ...$arguments]), true, 512, JSON_THROW_ON_ERROR);
    }

    /** A credit on the checking account, marked an advance on its own leg. */
    private function singleLeggedAdvance(int $amountCents, string $bookedAt = 'today -9 days'): Transaction
    {
        $credit = $this->add('checking', $amountCents, $bookedAt, label: 'VIR LIVRET A');
        $credit->setTransferKind(TransferKind::Advance);
        $this->flushWithoutTransactionEffects($this->em);

        return $credit;
    }

    private function accountId(string $ref): string
    {
        $account = $this->getFixture($ref);
        \assert($account instanceof Account);

        return (string) $account->getId();
    }

    public function testACallNobodyIsBoundToIsRefused(): void
    {
        $credit = $this->singleLeggedAdvance(60000);

        $data = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('livret'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        $this->em->refresh($credit);
        self::assertNull($credit->getAdvanceSource());
    }

    public function testAdvanceSourceIsStoredAndPublished(): void
    {
        $this->loginFixtureUser();
        $credit = $this->singleLeggedAdvance(60000);

        $data = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('livret'));

        self::assertTrue($data['success']);
        self::assertSame($this->accountId('livret'), $data['transaction']['advanceSourceAccountId']);
        $this->em->clear();
        $stored = $this->em->find(Transaction::class, $credit->getId());
        self::assertSame($this->accountId('livret'), $stored?->getAdvanceSourceAccountId());
        self::assertSame(TransferKind::Advance, $stored->getTransferKind());
        self::assertNull($stored->getCounterpart());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        $advances = self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user());
        self::assertSame(60000, $advances['totalOutstandingCents']);
        self::assertSame('Livret A', $advances['bySource'][0]['sourceAccountName']);
    }

    public function testAdvanceSourceIsClearedByHand(): void
    {
        $this->loginFixtureUser();
        $credit = $this->singleLeggedAdvance(60000);
        $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('livret'));

        $data = $this->update((string) $credit->getId(), clear: ['advanceSource']);

        self::assertNull($data['transaction']['advanceSourceAccountId']);
        self::assertSame(0, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user())['totalOutstandingCents']);
    }

    public function testAdvanceSourceMustBeASavingsAccountOfTheUser(): void
    {
        $this->loginFixtureUser();
        $credit = $this->singleLeggedAdvance(60000);
        $this->resetMercure();

        $checking = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('joint'));
        $theirs = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('other_livret'));

        self::assertArrayHasKey('error', $checking);
        self::assertArrayHasKey('error', $theirs);
        $this->em->refresh($credit);
        self::assertNull($credit->getAdvanceSource());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnInvestmentAccountIsASource(): void
    {
        $this->loginFixtureUser();
        $credit = $this->singleLeggedAdvance(60000);

        $data = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('pea'));

        self::assertSame($this->accountId('pea'), $data['transaction']['advanceSourceAccountId']);
    }

    public function testAdvanceSourceOnlyOnAdvanceOrRepayment(): void
    {
        $this->loginFixtureUser();
        $plain = $this->add('checking', 60000, 'today -9 days', label: 'VIR');
        [, $paired] = $this->transfer('livret', 'checking', 60000, 'today -9 days', TransferKind::Advance);

        $none = $this->update((string) $plain->getId(), advanceSourceAccountId: $this->accountId('livret'));
        $withLeg = $this->update((string) $paired->getId(), advanceSourceAccountId: $this->accountId('livret'));

        self::assertSame('advanceSourceAccountId goes with transferKind advance or repayment.', $none['error']);
        self::assertArrayHasKey('error', $withLeg);
        $this->em->refresh($plain);
        $this->em->refresh($paired);
        self::assertNull($plain->getAdvanceSource());
        self::assertNull($paired->getAdvanceSource());
    }

    /** The debit leg of an advance is read as such already: a source on it would count it twice. */
    public function testAdvanceSourceOnlyOnTheLegOnTheCheckingAccount(): void
    {
        $this->loginFixtureUser();
        $debit = $this->add('livret', -30000, 'today -9 days', label: 'VIR VERS COURANT');
        $debit->setTransferKind(TransferKind::Advance);
        $credit = $this->singleLeggedAdvance(60000);
        $this->flushWithoutTransactionEffects($this->em);

        $onDebit = $this->update((string) $debit->getId(), advanceSourceAccountId: $this->accountId('pea'));
        $ownAccount = $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('checking'));

        self::assertArrayHasKey('error', $onDebit);
        self::assertArrayHasKey('error', $ownAccount);
        self::assertSame(30000, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user())['totalOutstandingCents']);
    }

    public function testANewMarkingDropsTheSource(): void
    {
        $this->loginFixtureUser();
        $credit = $this->singleLeggedAdvance(60000);
        $this->update((string) $credit->getId(), advanceSourceAccountId: $this->accountId('livret'));

        $data = $this->update((string) $credit->getId(), transferKind: 'none');

        self::assertNull($data['transaction']['advanceSourceAccountId']);
    }

    public function testMarkingAndSourceGoTogether(): void
    {
        $this->loginFixtureUser();
        $credit = $this->add('checking', 60000, 'today -9 days', label: 'VIR LIVRET A');

        $data = $this->update((string) $credit->getId(), transferKind: 'advance', advanceSourceAccountId: $this->accountId('livret'));

        self::assertSame('advance', $data['transaction']['transferKind']);
        self::assertSame($this->accountId('livret'), $data['transaction']['advanceSourceAccountId']);
    }
}
