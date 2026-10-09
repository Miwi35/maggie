<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\DeleteTransactionCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Whichever door saves a transaction, the same effects follow (MAG-367): its
 * category, the transfer or rejection it belongs to, and the repair of the
 * other leg when one is removed.
 */
class TransactionEffectsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Import/fixtures/two_accounts.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function dispatch(object $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    private function stored(string $fixture): Transaction
    {
        $this->em()->clear();

        return $this->em()->getRepository(Transaction::class)->find($this->getFixture($fixture)->getId());
    }

    public function testChangingTheLabelToOneARuleClaimsCategorizesTheTransaction(): void
    {
        $this->dispatch(new UpdateTransactionCommand(
            userId: $this->userId(),
            transactionId: (string) $this->getFixture('plain_expense')->getId(),
            label: 'CARREFOUR MARKET 4412',
        ));

        $transaction = $this->stored('plain_expense');
        self::assertSame('Alimentation', $transaction->getCategory()?->getName());
        self::assertSame(CategorySource::Rule, $transaction->getCategorySource());
    }

    public function testACategorySetByHandSurvivesALabelChange(): void
    {
        $this->dispatch(new UpdateTransactionCommand(
            userId: $this->userId(),
            transactionId: (string) $this->getFixture('plain_expense')->getId(),
            categoryId: (string) $this->getFixture('food')->getId(),
        ));
        $this->dispatch(new UpdateTransactionCommand(
            userId: $this->userId(),
            transactionId: (string) $this->getFixture('plain_expense')->getId(),
            label: 'AUTRE LIBELLE',
        ));

        self::assertSame(CategorySource::Manual, $this->stored('plain_expense')->getCategorySource());
    }

    public function testTheSecondLegCreatedAfterTheFirstIsPairedWithIt(): void
    {
        $this->dispatch(new CreateTransactionCommand(
            userId: $this->userId(),
            accountId: (string) $this->getFixture('savings')->getId(),
            amountCents: -50000,
            label: 'VIR VERS COURANT',
            bookedAt: '2026-10-02',
        ));
        $this->dispatch(new CreateTransactionCommand(
            userId: $this->userId(),
            accountId: (string) $this->getFixture('checking')->getId(),
            amountCents: 50000,
            label: 'VIR DU LIVRET',
            bookedAt: '2026-10-03',
        ));

        $this->em()->clear();
        $out = $this->em()->getRepository(Transaction::class)->findOneBy(['label' => 'VIR VERS COURANT']);
        $in = $this->em()->getRepository(Transaction::class)->findOneBy(['label' => 'VIR DU LIVRET']);
        self::assertSame(TransferKind::Internal, $out->getTransferKind());
        self::assertSame((string) $in->getId(), (string) $out->getCounterpart()?->getId());
    }

    public function testRemovingALegReleasesTheOtherOneAndTellsTheScreens(): void
    {
        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');
        $out->markAsInternalTransfer($in, \Maggie\Finance\Enum\TransferSource::Auto);
        $this->em()->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->dispatch(new DeleteTransactionCommand($this->userId(), (string) $out->getId()));

        $remaining = $this->stored('transfer_in');
        self::assertSame(TransferKind::None, $remaining->getTransferKind());
        self::assertNull($remaining->getCounterpart());
        $this->assertMercureUpdatePublished('/transactions/'.$remaining->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $remaining->getId());
        $this->assertElasticsearchDeleteDispatched();
    }
}
