<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Mcp\Tool\ManageOperationsTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CategorizationRuleToolsTest extends KernelTestCase
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
    }

    private function tool(): ManageOperationsTool
    {
        return self::getContainer()->get(ManageOperationsTool::class);
    }

    public function testCreateRulePersistsAndPublishes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('leisure');

        $result = $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $category->getId(), priority: 5);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('UGC', $data['operation']['labelPattern']);
        self::assertSame('contains', $data['operation']['matchType']);
        self::assertSame((string) $category->getId(), $data['operation']['categoryId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(RecurringOperation::class)->findAll());

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    private function categoryOfTransaction(string $fixture): ?string
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $category = $em->find(Transaction::class, $this->getFixture($fixture)->getId())->getCategory();

        return $category ? (string) $category->getId() : null;
    }

    public function testCreateWithApplyToExistingFilesTheHistory(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');

        $data = json_decode(
            $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $leisure->getId(), applyToExisting: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame((string) $leisure->getId(), $this->categoryOfTransaction('uncategorized_cinema'));
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testCreateWithoutApplyToExistingLeavesTheHistoryAlone(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();

        $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $this->getFixture('leisure')->getId());

        self::assertNull($this->categoryOfTransaction('uncategorized_cinema'));
        $this->assertNothingPublishedOn('/transactions/');
    }

    public function testUpdateWithApplyToExistingUsesTheNewCriteria(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');

        $data = json_decode(
            $this->tool()('update', operationId: (string) $this->getFixture('rule_carrefour')->getId(), labelPattern: 'UGC', categoryId: (string) $leisure->getId(), applyToExisting: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame((string) $leisure->getId(), $this->categoryOfTransaction('uncategorized_cinema'));
        self::assertNull($this->categoryOfTransaction('uncategorized_carrefour'));
    }

    public function testPreviewListsWhatTheCriteriaWouldCatchAndWritesNothing(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');

        $data = json_decode(
            $this->tool()('preview', labelPattern: 'UGC', categoryId: (string) $leisure->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame(1, $data['total']);
        self::assertSame(1, $data['changeCount']);
        self::assertSame('UGC CINE CITE', $data['matches'][0]['label']);
        self::assertTrue($data['matches'][0]['wouldChange']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(RecurringOperation::class)->findAll());
        self::assertNull($this->categoryOfTransaction('uncategorized_cinema'));
        $this->assertNothingPublishedOn('/transactions/');
        $this->assertNothingPublishedOn('/recurring_operations/');
    }

    public function testPreviewRequiresAPattern(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('preview'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testPreviewRejectsAnInvertedAmountRange(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('preview', labelPattern: 'UGC', minAmountCents: 5000, maxAmountCents: 1000),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testCreateRequiresPatternAndCategory(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('create', labelPattern: 'UGC'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testCreateRejectsAnInvertedAmountRange(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('leisure');

        $data = json_decode(
            $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $category->getId(), minAmountCents: 5000, maxAmountCents: 1000),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('minimum amount', $data['error']);
    }

    public function testListReturnsRulesByPriority(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('leisure');
        $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $category->getId(), priority: 99);

        $data = json_decode($this->tool()('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertCount(2, $data['operations']);
        self::assertSame('UGC', $data['operations'][0]['labelPattern']);
    }

    public function testUpdateChangesTheRuleAndPublishes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('rule_carrefour');

        $data = json_decode(
            $this->tool()('update', operationId: (string) $rule->getId(), labelPattern: 'CARREFOUR MARKET', isActive: false),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('CARREFOUR MARKET', $data['operation']['labelPattern']);
        self::assertFalse($data['operation']['isActive']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertFalse($em->find(RecurringOperation::class, $rule->getId())->isActive());

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testClearRemovesAnAmountBound(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('ranged_rule');

        $data = json_decode(
            $this->tool()('update', operationId: (string) $rule->getId(), clear: ['maxAmountCents', 'labelPattern']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['operation']['maxAmountCents']);
        self::assertSame(1000, $data['operation']['minAmountCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(RecurringOperation::class, $rule->getId());
        self::assertNull($refreshed->getMaxAmountCents());
        self::assertSame(1000, $refreshed->getMinAmountCents());
        self::assertNull($refreshed->getLabelPattern(), 'the debit range is enough to recognise the rule');

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testClearBothBoundsOpensTheRange(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('ranged_rule');

        $data = json_decode(
            $this->tool()('update', operationId: (string) $rule->getId(), clear: ['minAmountCents', 'maxAmountCents']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['operation']['minAmountCents']);
        self::assertNull($data['operation']['maxAmountCents']);
    }

    public function testClearOnAnUnknownRuleReturnsAnError(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('update', operationId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['minAmountCents']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testDeleteRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('rule_carrefour');

        $data = json_decode($this->tool()('delete', operationId: (string) $rule->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(RecurringOperation::class, $rule->getId()));

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchDeleteDispatched('recurring_operations');
    }

    public function testApplyCategorizesTheMatchingHistoryOnly(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $food = $this->getFixture('food');
        $carrefour = $this->getFixture('uncategorized_carrefour');
        $cinema = $this->getFixture('uncategorized_cinema');

        $data = json_decode($this->tool()('apply'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame(1, $data['categorized']);
        self::assertSame(2, $data['scanned']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $categorized = $em->find(Transaction::class, $carrefour->getId());
        self::assertSame((string) $food->getId(), (string) $categorized->getCategory()->getId());
        self::assertSame(CategorySource::Rule, $categorized->getCategorySource());
        self::assertNotNull($categorized->getCategorizedAt());

        // No rule claims the cinema debit, so it stays uncategorized.
        self::assertNull($em->find(Transaction::class, $cinema->getId())->getCategory());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testApplyLeavesAManualCategoryAlone(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $manual = $this->getFixture('manual_carrefour');
        $leisure = $this->getFixture('leisure');

        $this->tool()('apply');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // Its label matches the CARREFOUR rule, but a human filed it under Loisirs.
        $refreshed = $em->find(Transaction::class, $manual->getId());
        self::assertSame((string) $leisure->getId(), (string) $refreshed->getCategory()->getId());
        self::assertSame(CategorySource::Manual, $refreshed->getCategorySource());
    }

    public function testHighestPriorityRuleWins(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');
        $carrefour = $this->getFixture('uncategorized_carrefour');

        // Same label, higher priority, different category.
        $this->tool()('create', labelPattern: 'CARREFOUR', categoryId: (string) $leisure->getId(), priority: 50);

        $this->tool()('apply');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(
            (string) $leisure->getId(),
            (string) $em->find(Transaction::class, $carrefour->getId())->getCategory()->getId(),
        );
    }

    public function testAmountRangeAndDirectionNarrowTheMatch(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');
        $cinema = $this->getFixture('uncategorized_cinema');

        // The cinema debit is 12,00 €, outside the 20..50 € window.
        $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $leisure->getId(), minAmountCents: 2000, maxAmountCents: 5000);
        $this->tool()('apply');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Transaction::class, $cinema->getId())->getCategory());
    }

    public function testACreditRuleIgnoresADebit(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $salary = $this->getFixture('salary');
        $cinema = $this->getFixture('uncategorized_cinema');

        $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $salary->getId(), direction: 'credit');
        $this->tool()('apply');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Transaction::class, $cinema->getId())->getCategory());
    }

    public function testLearnCreatesARuleAndFilesTheTransaction(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $leisure = $this->getFixture('leisure');
        $cinema = $this->getFixture('uncategorized_cinema');

        $data = json_decode(
            $this->tool()('learn', categoryId: (string) $leisure->getId(), transactionId: (string) $cinema->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('UGC CINE CITE', $data['operation']['labelPattern']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Transaction::class, $cinema->getId());
        self::assertSame((string) $leisure->getId(), (string) $refreshed->getCategory()->getId());
        self::assertSame(CategorySource::Manual, $refreshed->getCategorySource());

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertMercureUpdatePublished('/transactions/');
    }

    public function testLearnStripsTheNoisyTailOfABankLabel(): void
    {
        self::assertSame('CARREFOUR MARKET', ManageOperationsTool::patternFromLabel('CARREFOUR MARKET 4412'));
        self::assertSame('SNCF CONNECT', ManageOperationsTool::patternFromLabel('SNCF CONNECT  12/09 REF 8891'));
        self::assertSame('UGC CINE CITE', ManageOperationsTool::patternFromLabel('UGC CINE CITE BERCY'));
        self::assertSame('4412', ManageOperationsTool::patternFromLabel('4412'));
    }

    public function testANewTransactionIsCategorizedOnCreation(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');
        $food = $this->getFixture('food');

        $transactionTool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $transactionTool('create', accountId: (string) $account->getId(), amountCents: -3200, label: 'CARREFOUR CITY 88', bookedAt: '2026-09-10'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame((string) $food->getId(), $data['transaction']['categoryId']);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('simulate'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testCreateAnAmountOnlyRuleFilesTheSalaryOnItsAmount(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/amount_only.yaml');
        $this->loginFixtureUser();
        $salary = $this->getFixture('salary');

        $data = json_decode($this->tool()('create', categoryId: (string) $salary->getId(), direction: 'credit', minAmountCents: 427500, maxAmountCents: 522500, applyToExisting: true), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($data['operation']['labelPattern']);
        self::assertSame('credit', $data['operation']['direction']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(RecurringOperation::class)->findAll());
        self::assertSame((string) $salary->getId(), $this->categoryOfTransaction('pay_october'));
        self::assertSame((string) $salary->getId(), $this->categoryOfTransaction('pay_september'));
        self::assertNull($this->categoryOfTransaction('small_credit'));
        self::assertNull($this->categoryOfTransaction('same_size_debit'));
        self::assertNull($this->categoryOfTransaction('same_size_transfer'));
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testCreateWithoutLabelNorDirectedAmountIsRefusedWithTheStateGiven(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/amount_only.yaml');
        $this->loginFixtureUser();
        $category = (string) $this->getFixture('salary')->getId();

        foreach ([
            [['minAmountCents' => 427500, 'maxAmountCents' => 522500], 'any'],
            [['direction' => 'credit'], 'credit'],
            [[], 'any'],
        ] as [$args, $direction]) {
            $data = json_decode($this->tool()('create', ...array_merge(['categoryId' => $category], $args)), true, 512, JSON_THROW_ON_ERROR);

            self::assertArrayHasKey('error', $data);
            self::assertSame(RecurringOperation::UNRECOGNISABLE, $data['error']);
            self::assertSame($direction, $data['current']['direction']);
            self::assertNull($data['current']['labelPattern']);
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(RecurringOperation::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateThatWouldLeaveNothingToRecogniseByIsRefusedAndChangesNothing(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('rule_carrefour');

        $data = json_decode($this->tool()('update', operationId: (string) $rule->getId(), clear: ['labelPattern']), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString(RecurringOperation::UNRECOGNISABLE, $data['error']);
        self::assertSame('CARREFOUR', $data['current']['labelPattern']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('CARREFOUR', $em->find(RecurringOperation::class, $rule->getId())->getLabelPattern());
        $this->assertMercureUpdateCount(0);
    }

    public function testPreviewOfAnAmountOnlyRuleListsTheLinesItCatches(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/amount_only.yaml');
        $this->loginFixtureUser();

        $data = json_decode($this->tool()('preview', direction: 'credit', minAmountCents: 427500, maxAmountCents: 522500, categoryId: (string) $this->getFixture('salary')->getId()), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame(2, $data['total']);
        self::assertEqualsCanonicalizing(['VIR SEPA XYZ', 'SALAIRE SEPTEMBRE ABC'], array_column($data['matches'], 'label'));

        $refused = json_decode($this->tool()('preview', minAmountCents: 427500), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(RecurringOperation::UNRECOGNISABLE, $refused['error']);
    }
}
