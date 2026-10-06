<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateCategorizationRuleHandlerTest extends KernelTestCase
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

    private function dispatch(UpdateCategorizationRuleCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('ranged_rule')->getId();
    }

    private function reload(): CategorizationRule
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(CategorizationRule::class)->find($this->getFixture('ranged_rule')->getId());
    }

    public function testClearingTheMaximumKeepsTheMinimum(): void
    {
        $this->dispatch(new UpdateCategorizationRuleCommand(
            userId: $this->userId(),
            categorizationRuleId: $this->id(),
            clearFields: ['maxAmountCents'],
        ));

        $rule = $this->reload();
        self::assertNull($rule->getMaxAmountCents());
        self::assertSame(1000, $rule->getMinAmountCents());
        self::assertSame('CARREFOUR', $rule->getLabelPattern());
        self::assertSame(10, $rule->getPriority());
        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testClearingBothBoundsOpensTheRange(): void
    {
        $this->dispatch(new UpdateCategorizationRuleCommand(
            userId: $this->userId(),
            categorizationRuleId: $this->id(),
            clearFields: ['minAmountCents', 'maxAmountCents'],
        ));

        $rule = $this->reload();
        self::assertNull($rule->getMinAmountCents());
        self::assertNull($rule->getMaxAmountCents());
        self::assertTrue($rule->isActive());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateCategorizationRuleCommand(
            userId: $this->userId(),
            categorizationRuleId: $this->id(),
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
        $this->expectExceptionMessage('Categorization rule not found');

        $this->dispatch(new UpdateCategorizationRuleCommand(
            userId: $this->userId(),
            categorizationRuleId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
            clearFields: ['minAmountCents'],
        ));
    }
}
