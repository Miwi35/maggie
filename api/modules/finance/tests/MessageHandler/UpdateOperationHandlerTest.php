<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\UpdateOperationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateOperationHandlerTest extends KernelTestCase
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

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    private function dispatch(UpdateOperationCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('ranged_rule')->getId();
    }

    private function reload(): RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringOperation::class)->find($this->getFixture('ranged_rule')->getId());
    }

    public function testClearingTheMaximumKeepsTheMinimum(): void
    {
        $this->dispatch(new UpdateOperationCommand(
            userId: $this->userId(),
            operationId: $this->id(),
            clearFields: ['maxAmountCents'],
        ));

        $rule = $this->reload();
        self::assertNull($rule->getMaxAmountCents());
        self::assertSame(1000, $rule->getMinAmountCents());
        self::assertSame('CARREFOUR', $rule->getLabelPattern());
        self::assertSame(10, $rule->getPriority());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testClearingBothBoundsOpensTheRange(): void
    {
        $this->dispatch(new UpdateOperationCommand(
            userId: $this->userId(),
            operationId: $this->id(),
            clearFields: ['minAmountCents', 'maxAmountCents'],
        ));

        $rule = $this->reload();
        self::assertNull($rule->getMinAmountCents());
        self::assertNull($rule->getMaxAmountCents());
        self::assertTrue($rule->isActive());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateOperationCommand(
            userId: $this->userId(),
            operationId: $this->id(),
            priority: 20,
        ));

        $rule = $this->reload();
        self::assertSame(20, $rule->getPriority());
        self::assertSame(1000, $rule->getMinAmountCents());
        self::assertSame(5000, $rule->getMaxAmountCents());
    }

    public function testUnknownRuleFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Operation not found');

        $this->dispatch(new UpdateOperationCommand(
            userId: $this->userId(),
            operationId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
            clearFields: ['minAmountCents'],
        ));
    }
}
