<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateTransactionHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('clearable_fields.yaml');
    }

    private function dispatch(UpdateTransactionCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('groceries')->getId();
    }

    private function reload(): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Transaction::class)->find($this->getFixture('groceries')->getId());
    }

    public function testClearFieldsRemovesTheCategory(): void
    {
        $this->dispatch(new UpdateTransactionCommand(
            transactionId: $this->id(),
            clearFields: ['categoryId'],
        ));

        $transaction = $this->reload();
        self::assertNull($transaction->getCategory());
        self::assertSame(CategorySource::None, $transaction->getCategorySource());
        self::assertNull($transaction->getCategorizedAt());
        self::assertSame('Supermarché', $transaction->getLabel());
        self::assertSame(-4599, $transaction->getAmountCents());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAnEmptyCategoryIdStillRemovesTheCategory(): void
    {
        $this->dispatch(new UpdateTransactionCommand(
            transactionId: $this->id(),
            categoryId: '',
        ));

        $transaction = $this->reload();
        self::assertNull($transaction->getCategory());
        self::assertSame(CategorySource::None, $transaction->getCategorySource());
    }

    public function testNullCategoryWithoutClearIsLeftUntouched(): void
    {
        $this->dispatch(new UpdateTransactionCommand(
            transactionId: $this->id(),
            label: 'Renamed',
        ));

        $transaction = $this->reload();
        self::assertSame('Renamed', $transaction->getLabel());
        self::assertSame('Alimentation', $transaction->getCategory()?->getName());
        self::assertSame(CategorySource::Manual, $transaction->getCategorySource());
    }

    public function testUnknownTransactionFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Transaction not found');

        $this->dispatch(new UpdateTransactionCommand(
            transactionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
            clearFields: ['categoryId'],
        ));
    }
}
