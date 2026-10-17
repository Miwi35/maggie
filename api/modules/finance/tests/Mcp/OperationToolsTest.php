<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageOperationsTool;
use Maggie\Finance\MessageHandler\UpdateOperationHandler;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * `manage_operations` (MAG-478): one tool for the operations with or without
 * recurrence, in place of manage_recurring_operations and
 * manage_categorization_rules.
 */
class OperationToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(dirname(__DIR__).'/Api/fixtures/operations.yaml');
    }

    public function testACallNobodyIsBoundToIsRefused(): void
    {
        $data = $this->call('create', categoryId: $this->id('food'), labelPattern: 'LIDL');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame(3, $this->rows());
    }

    public function testTheListHoldsTheSeriesAndTheOthersAndNarrowsOnTheRecurrence(): void
    {
        $this->loginFixtureUser();

        $all = $this->call('list')['operations'];
        self::assertSame([$this->id('gym'), $this->id('rule_carrefour')], array_column($all, 'id'));
        self::assertSame([true, false], array_column($all, 'recurring'));
        self::assertSame(-36000, $all[0]['yearlyCostCents']);
        self::assertNull($all[1]['period']);

        self::assertSame([$this->id('gym')], array_column($this->call('list', recurring: true)['operations'], 'id'));
        self::assertSame([$this->id('rule_carrefour')], array_column($this->call('list', recurring: false)['operations'], 'id'));
    }

    public function testAnOperationWithoutRecurrenceIsCreatedAndFilesTheNextLine(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', categoryId: $this->id('food'), labelPattern: 'LIDL', direction: 'debit', priority: 5, mandatory: true, kind: 'none');

        self::assertTrue($data['success']);
        self::assertFalse($data['operation']['recurring']);
        $stored = $this->find($data['operation']['id']);
        self::assertNotNull($stored);
        self::assertFalse($stored->hasRecurrence());
        self::assertSame(5, $stored->getPriority());
        self::assertTrue($stored->getQualification()->isMandatory());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $data['operation']['id']);

        $applied = $this->call('apply', operationId: $data['operation']['id']);

        self::assertSame(1, $applied['categorized']);
        $line = $this->line('lidl_line');
        self::assertSame('rule', $line->getQualifiedBy());
        self::assertSame($data['operation']['id'], (string) $line->getQualifiedByRule()?->getId());
        self::assertTrue($line->getQualification()->isMandatory());
    }

    public function testASeriesIsCreatedWithTheWholeRecurrence(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', categoryId: $this->id('subscriptions'), label: 'Flixo', counterpartyName: 'FLIXO SAS', period: 'monthly', anchorOn: '2027-01-12', referenceAmountCents: -1349, accountId: $this->id('checking'));

        self::assertTrue($data['operation']['recurring']);
        self::assertSame('2027-01-12', $data['operation']['anchorOn']);
        self::assertSame((string) $this->getFixture('checking')->getId(), $data['operation']['accountId']);
        self::assertTrue($this->find($data['operation']['id'])?->hasRecurrence());
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, $data['operation']['id']);
    }

    public function testAPartOfARecurrenceIsRefusedWithWhatIsMissing(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', categoryId: $this->id('subscriptions'), label: 'Flixo', labelPattern: 'FLIXO', anchorOn: '2027-01-12', referenceAmountCents: -1349);

        self::assertStringContainsString(RecurringOperation::PARTIAL_RECURRENCE, $data['error']);
        self::assertStringContainsString('Missing: period, accountId.', $data['error']);
        self::assertSame('2027-01-12', $data['current']['anchorOn']);
        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnOperationWithNothingToRecogniseByIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', categoryId: $this->id('food'), direction: 'debit');

        self::assertSame(RecurringOperation::UNRECOGNISABLE, $data['error']);
        self::assertSame(3, $this->rows());
    }

    public function testAnOperationKeepsItsRecurrenceOrItsLackOfOne(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', operationId: $this->id('rule_carrefour'), label: 'Courses', period: 'monthly', anchorOn: '2027-01-03', referenceAmountCents: -4599, accountId: $this->id('checking'));

        self::assertSame(UpdateOperationHandler::RECURRENCE_KEPT, $data['error']);
        self::assertSame('CARREFOUR', $data['current']['labelPattern']);
        self::assertFalse($this->find($this->id('rule_carrefour'))?->hasRecurrence());
    }

    public function testApplyRunsTheSeriesThenTheOthersOverTheHistory(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('apply');

        self::assertTrue($data['success']);
        self::assertSame(1, $data['categorized']);
        self::assertSame('rule', $this->line('carrefour_line')->getQualifiedBy());
        self::assertSame($this->id('rule_carrefour'), (string) $this->line('carrefour_line')->getQualifiedByRule()?->getId());
    }

    public function testPreviewReadsAnOperationWithOrWithoutRecurrence(): void
    {
        $this->loginFixtureUser();

        // A new one comes after the saved CARREFOUR, which outranks it.
        $new = $this->call('preview', labelPattern: 'CARREFOUR', categoryId: $this->id('food'));
        self::assertSame(1, $new['total']);
        self::assertSame(0, $new['changeCount']);
        // The saved one, edited, takes its own place.
        $edited = $this->call('preview', operationId: $this->id('rule_carrefour'), labelPattern: 'CARREFOUR', categoryId: $this->id('food'));
        self::assertSame(1, $edited['changeCount']);

        $series = $this->call('preview', labelPattern: 'LIDL', referenceAmountCents: -2310);
        self::assertSame(1, $series['total']);
        self::assertSame($this->id('lidl_line'), $series['matches'][0]['transactionId']);
    }

    public function testAnotherUsersOperationCannotBeTouched(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('not found', $this->call('update', operationId: $this->id('other_rule'), priority: 1)['error']);
        self::assertStringContainsString('not found', $this->call('delete', operationId: $this->id('other_rule'))['error']);
        self::assertStringContainsString('not found', $this->call('apply', operationId: $this->id('other_rule'))['error']);
        self::assertNotNull($this->find($this->id('other_rule')));
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageOperationsTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function find(string $id): ?RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(RecurringOperation::class, $id);
    }

    private function line(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $line = $em->find(Transaction::class, $this->getFixture($fixture)->getId());
        self::assertInstanceOf(Transaction::class, $line);

        return $line;
    }

    private function rows(): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringOperation::class)->count([]);
    }
}
