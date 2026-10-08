<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Mcp\Tool\ManageAccountsTool;
use Maggie\Finance\Mcp\Tool\ManageCategoriesTool;
use Maggie\Finance\Mcp\Tool\ManageRecurringOperationsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class RecurringOperationToolsTest extends KernelTestCase
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
        $this->loadFixtures('recurring_operation.yaml');
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageRecurringOperationsTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function reload(string $fixture): ?RecurringOperation
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(RecurringOperation::class, $this->getFixture($fixture)->getId());
    }

    private function rows(): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringOperation::class)->count([]);
    }

    public function testACallNobodyIsBoundToIsRefused(): void
    {
        $data = $this->call('list');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('recurringOperations', $data);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('detect');

        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->loginFixtureUser();
        $before = $this->rows();

        $data = $this->call(
            'create',
            label: 'Abonnement Flixo',
            categoryId: $this->id('subscriptions'),
            accountId: $this->id('checking'),
            referenceAmountCents: -1349,
            anchorOn: '2027-01-12',
            counterpartyName: 'Flixo',
        );

        self::assertTrue($data['success']);
        $operation = $data['recurringOperation'];
        self::assertSame(-1349, $operation['monthlyCostCents']);
        self::assertSame(-16188, $operation['yearlyCostCents']);
        self::assertSame('EUR', $operation['currency']);
        self::assertSame('Abonnements', $operation['categoryName']);
        self::assertMatchesRegularExpression('/^\d{4}-\d{2}-12$/', $operation['nextOccurrenceOn']);
        self::assertGreaterThan((new \DateTimeImmutable('today'))->format('Y-m-d'), $operation['nextOccurrenceOn']);

        self::assertSame($before + 1, $this->rows());
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $stored = $em->find(RecurringOperation::class, $operation['id']);
        self::assertSame('flixo', $stored?->getCounterpartyKey());
        self::assertSame(RecurrencePeriod::Monthly, $stored->getPeriod());
        self::assertSame(DayRule::FixedDay, $stored->getDayRule());
        self::assertSame(20, $stored->getAmountTolerancePercent());

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testCreateWithoutARequiredArgumentIsRefused(): void
    {
        $this->loginFixtureUser();
        $before = $this->rows();

        $data = $this->call('create', label: 'Abonnement Flixo', categoryId: $this->id('subscriptions'), referenceAmountCents: -1349, anchorOn: '2027-01-12');

        self::assertStringContainsString('accountId', $data['error']);
        self::assertSame($before, $this->rows());
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function refusedArguments(): iterable
    {
        yield 'zero amount' => [['referenceAmountCents' => 0], 'referenceAmountCents'];
        yield 'tolerance out of bounds' => [['amountTolerancePercent' => 150], 'amountTolerancePercent'];
        yield 'end before the anchor' => [['endsOn' => '2027-01-01'], 'endsOn'];
        yield 'nothing to recognise it by' => [['counterpartyName' => null], 'counterpartyName'];
        yield 'unknown period' => [['period' => 'daily'], 'daily'];
        yield 'not a date' => [['anchorOn' => '12/01/2027'], 'anchorOn'];
        yield 'income category on an expense' => [['categoryId' => 'salary'], 'income category'];
        yield 'weekly on the last day of the month' => [['period' => 'weekly', 'dayRule' => 'last_day_of_month'], 'dayRule'];
    }

    /** @param array<string, mixed> $overrides */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusedArguments')]
    public function testAnInvalidSeriesIsRefused(array $overrides, string $mention): void
    {
        $this->loginFixtureUser();
        $before = $this->rows();

        if (isset($overrides['categoryId'])) {
            $overrides['categoryId'] = $this->id($overrides['categoryId']);
        }

        $data = $this->call('create', ...array_merge([
            'label' => 'Abonnement Flixo',
            'categoryId' => $this->id('subscriptions'),
            'accountId' => $this->id('checking'),
            'referenceAmountCents' => -1349,
            'anchorOn' => '2027-01-12',
            'counterpartyName' => 'Flixo',
        ], $overrides));

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString($mention, $data['error']);
        self::assertSame($before, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUsersCategoryOrAccountIsRefused(): void
    {
        $this->loginFixtureUser();
        $before = $this->rows();

        $base = ['label' => 'Flixo', 'referenceAmountCents' => -1349, 'anchorOn' => '2027-01-12', 'counterpartyName' => 'Flixo'];

        $data = $this->call('create', ...$base + ['categoryId' => $this->id('other_secret'), 'accountId' => $this->id('checking')]);
        self::assertStringContainsString('Category not found', $data['error']);

        $data = $this->call('create', ...$base + ['categoryId' => $this->id('subscriptions'), 'accountId' => $this->id('other_checking')]);
        self::assertStringContainsString('Account not found', $data['error']);

        self::assertSame($before, $this->rows());
    }

    public function testListReturnsOnlyTheUsersSeries(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame(['Salle de sport'], array_column($data['recurringOperations'], 'label'));
        self::assertSame(-36000, $data['recurringOperations'][0]['yearlyCostCents']);
    }

    public function testUpdateChangesOnlyWhatIsGivenAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', recurringOperationId: $this->id('gym'), referenceAmountCents: -3500, period: 'quarterly', clear: ['endsOn']);

        self::assertTrue($data['success']);
        $refreshed = $this->reload('gym');
        self::assertSame(-3500, $refreshed?->getReferenceAmountCents());
        self::assertSame(RecurrencePeriod::Quarterly, $refreshed->getPeriod());
        self::assertNull($refreshed->getEndsOn());
        self::assertSame('Club Forme', $refreshed->getCounterpartyName());

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testARefusedUpdateLeavesTheSeriesAsItWas(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', recurringOperationId: $this->id('gym'), clear: ['counterpartyName']);

        self::assertArrayHasKey('error', $data);
        self::assertSame('Club Forme', $this->reload('gym')?->getCounterpartyName());
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateOrDeleteWithoutAnIdIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('recurringOperationId', $this->call('update', label: 'X')['error']);
        self::assertStringContainsString('recurringOperationId', $this->call('delete')['error']);
    }

    public function testAnotherUsersSeriesCannotBeTouched(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('not found', $this->call('update', recurringOperationId: $this->id('other_rent'), referenceAmountCents: -1)['error']);
        self::assertStringContainsString('not found', $this->call('delete', recurringOperationId: $this->id('other_rent'))['error']);

        self::assertSame(-90000, $this->reload('other_rent')?->getReferenceAmountCents());
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', recurringOperationId: $this->id('gym'));

        self::assertTrue($data['success']);
        self::assertNull($this->reload('gym'));
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchDeleteDispatched('recurring_operations');
    }

    /**
     * The database cascade takes the series down with its category or its
     * account; the index must forget it too, or the list keeps showing it.
     */
    public function testDeletingItsCategoryRemovesTheSeriesFromTheIndex(): void
    {
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $data = json_decode($tool('delete', categoryId: $this->id('subscriptions')), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($this->reload('gym'));
        self::assertContains(['recurring_operations', $this->id('gym')], $this->deletedDocuments());
    }

    public function testDeletingItsAccountRemovesTheSeriesFromTheIndex(): void
    {
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $data = json_decode($tool('delete', accountId: $this->id('checking')), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($this->reload('gym'));
        self::assertContains(['recurring_operations', $this->id('gym')], $this->deletedDocuments());
    }

    /** @return list<array{string, string}> */
    private function deletedDocuments(): array
    {
        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = [$message->indexName, $message->documentId];
            }
        }

        return $deleted;
    }
}
