<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
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

    private function tool(): ManageCategorizationRulesTool
    {
        return self::getContainer()->get(ManageCategorizationRulesTool::class);
    }

    public function testCreateRulePersistsAndPublishes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('leisure');

        $result = $this->tool()('create', labelPattern: 'UGC', categoryId: (string) $category->getId(), priority: 5);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('UGC', $data['rule']['labelPattern']);
        self::assertSame('contains', $data['rule']['matchType']);
        self::assertSame((string) $category->getId(), $data['rule']['categoryId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(CategorizationRule::class)->findAll());

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
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
            $this->tool()('update', categorizationRuleId: (string) $this->getFixture('rule_carrefour')->getId(), labelPattern: 'UGC', categoryId: (string) $leisure->getId(), applyToExisting: true),
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
        self::assertCount(1, $em->getRepository(CategorizationRule::class)->findAll());
        self::assertNull($this->categoryOfTransaction('uncategorized_cinema'));
        $this->assertNothingPublishedOn('/transactions/');
        $this->assertNothingPublishedOn('/categorization_rules/');
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

        self::assertCount(2, $data['rules']);
        self::assertSame('UGC', $data['rules'][0]['labelPattern']);
    }

    public function testUpdateChangesTheRuleAndPublishes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('rule_carrefour');

        $data = json_decode(
            $this->tool()('update', categorizationRuleId: (string) $rule->getId(), labelPattern: 'CARREFOUR MARKET', isActive: false),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('CARREFOUR MARKET', $data['rule']['labelPattern']);
        self::assertFalse($data['rule']['isActive']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertFalse($em->find(CategorizationRule::class, $rule->getId())->isActive());

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testClearRemovesAnAmountBound(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('ranged_rule');

        $data = json_decode(
            $this->tool()('update', categorizationRuleId: (string) $rule->getId(), clear: ['maxAmountCents', 'labelPattern']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['rule']['maxAmountCents']);
        self::assertSame(1000, $data['rule']['minAmountCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(CategorizationRule::class, $rule->getId());
        self::assertNull($refreshed->getMaxAmountCents());
        self::assertSame(1000, $refreshed->getMinAmountCents());
        self::assertSame('CARREFOUR', $refreshed->getLabelPattern(), 'Required fields cannot be cleared');

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testClearBothBoundsOpensTheRange(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();
        $rule = $this->getFixture('ranged_rule');

        $data = json_decode(
            $this->tool()('update', categorizationRuleId: (string) $rule->getId(), clear: ['minAmountCents', 'maxAmountCents']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['rule']['minAmountCents']);
        self::assertNull($data['rule']['maxAmountCents']);
    }

    public function testClearOnAnUnknownRuleReturnsAnError(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            $this->tool()('update', categorizationRuleId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['minAmountCents']),
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

        $data = json_decode($this->tool()('delete', categorizationRuleId: (string) $rule->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(CategorizationRule::class, $rule->getId()));

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchDeleteDispatched('categorization_rules');
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
        self::assertSame('UGC CINE CITE', $data['rule']['labelPattern']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Transaction::class, $cinema->getId());
        self::assertSame((string) $leisure->getId(), (string) $refreshed->getCategory()->getId());
        self::assertSame(CategorySource::Manual, $refreshed->getCategorySource());

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertMercureUpdatePublished('/transactions/');
    }

    public function testLearnStripsTheNoisyTailOfABankLabel(): void
    {
        self::assertSame('CARREFOUR MARKET', ManageCategorizationRulesTool::patternFromLabel('CARREFOUR MARKET 4412'));
        self::assertSame('SNCF CONNECT', ManageCategorizationRulesTool::patternFromLabel('SNCF CONNECT  12/09 REF 8891'));
        self::assertSame('UGC CINE CITE', ManageCategorizationRulesTool::patternFromLabel('UGC CINE CITE BERCY'));
        self::assertSame('4412', ManageCategorizationRulesTool::patternFromLabel('4412'));
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
}
