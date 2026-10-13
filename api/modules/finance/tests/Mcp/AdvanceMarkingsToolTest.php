<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The hard guarantees the owner gives by hand through manage_transactions
 * (MAG-277): `funding` on the regular top-up, and `repaysTransactionId` on a
 * repayment, instead of a heuristic that is right most of the time.
 */
class AdvanceMarkingsToolTest extends KernelTestCase
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
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function update(string $transactionId, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        return json_decode($tool(...['action' => 'update', 'transactionId' => $transactionId, ...$arguments]), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array{Transaction, Transaction} the debit leg on the checking account, the credit leg on the livret */
    private function unpairedTransferBack(int $amountCents, string $bookedAt = 'today'): array
    {
        return [
            $this->add('checking', -$amountCents, $bookedAt, label: 'Virement émis'),
            $this->add('livret', $amountCents, $bookedAt, label: 'Virement reçu'),
        ];
    }

    public function testFundingIsMarkedByHandAsTheUsersOwnDecision(): void
    {
        [$out, $in] = $this->unpairedTransferBack(20000);

        $data = $this->update((string) $out->getId(), transferKind: 'funding', counterpartId: (string) $in->getId());

        self::assertTrue($data['success']);
        self::assertSame('funding', $data['transaction']['transferKind']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        self::assertSame('Approvisionnement', $data['transaction']['transferNote']);
        self::assertSame((string) $in->getId(), $data['transaction']['counterpartId']);
        $this->em->refresh($in);
        self::assertSame(TransferKind::Funding, $in->getTransferKind());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    /** The same amount as an open advance: only the marking keeps it from being a repayment. */
    public function testALineMarkedFundingIsNeverTakenForARepaymentWhateverItsAmount(): void
    {
        $this->transfer('livret', 'checking', 20000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(20000);
        $this->update((string) $out->getId(), transferKind: 'funding', counterpartId: (string) $in->getId());

        $report = self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user());

        self::assertSame(0, $report['repayments']);
        $this->em->refresh($out);
        self::assertSame(TransferKind::Funding, $out->getTransferKind());
        self::assertSame(20000, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user())['totalOutstandingCents']);
    }

    public function testARepaymentIsPointedAtItsAdvanceByHand(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(100000);

        $data = $this->update(
            (string) $out->getId(),
            transferKind: 'repayment',
            counterpartId: (string) $in->getId(),
            repaysTransactionId: (string) $advance->getId(),
        );

        self::assertTrue($data['success']);
        self::assertSame('repayment', $data['transaction']['transferKind']);
        self::assertSame((string) $advance->getId(), $data['transaction']['repaysTransactionId']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        $this->em->refresh($out);
        self::assertSame((string) $advance->getId(), (string) $out->getRepays()?->getId());

        $left = self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user());
        self::assertSame(100000, $left['advances'][0]['repaidCents']);
        self::assertSame(50000, $left['totalOutstandingCents']);
        $this->assertMercureUpdatePublished('/transactions/');
    }

    public function testRepaysNeedsTheRepaymentKind(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(100000);

        $data = $this->update((string) $out->getId(), transferKind: 'internal', counterpartId: (string) $in->getId(), repaysTransactionId: (string) $advance->getId());

        self::assertArrayHasKey('error', $data);
        self::assertSame(150000, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user())['totalOutstandingCents']);
    }

    public function testRepaysGoesOnTheDebitLegOnly(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(100000);

        $data = $this->update((string) $in->getId(), transferKind: 'repayment', counterpartId: (string) $out->getId(), repaysTransactionId: (string) $advance->getId());

        self::assertArrayHasKey('error', $data);
    }

    public function testRepaysMustNameAnAdvance(): void
    {
        [$notAnAdvance] = $this->transfer('livret', 'checking', 150000, 'today -9 days');
        [$out, $in] = $this->unpairedTransferBack(100000);

        $data = $this->update((string) $out->getId(), transferKind: 'repayment', counterpartId: (string) $in->getId(), repaysTransactionId: (string) $notAnAdvance->getId());

        self::assertArrayHasKey('error', $data);
    }

    public function testRepaysCannotNameTheAdvanceOfAnotherUser(): void
    {
        [$theirs] = $this->transfer('other_livret', 'other_checking', 150000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(100000);

        $data = $this->update((string) $out->getId(), transferKind: 'repayment', counterpartId: (string) $in->getId(), repaysTransactionId: (string) $theirs->getId());

        self::assertArrayHasKey('error', $data);
        self::assertSame(0, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user('other_user'))['advances'][0]['repaidCents']);
    }

    public function testTakingARepaymentBackOutFreesItsAdvance(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -9 days', TransferKind::Advance);
        [$out, $in] = $this->unpairedTransferBack(150000);
        $this->update((string) $out->getId(), transferKind: 'repayment', counterpartId: (string) $in->getId(), repaysTransactionId: (string) $advance->getId());

        $data = $this->update((string) $out->getId(), transferKind: 'none');

        self::assertSame('none', $data['transaction']['transferKind']);
        self::assertNull($data['transaction']['repaysTransactionId']);
        self::assertSame(150000, self::getContainer()->get(GetSavingsAdvances::class)->execute($this->user())['totalOutstandingCents']);
        self::assertSame(TransferSource::Manual, $out->getTransferSource());
    }
}
