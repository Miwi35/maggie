<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageCategoriesTool;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * A reference to another user's object is refused, and nothing is stored.
 */
class ForeignReferenceToolsTest extends KernelTestCase
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
        $this->loadFixtures('foreign_references.yaml');
        $this->loginFixtureUser();
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function assertRefused(string $result): void
    {
        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('success', $data);
        self::assertStringNotContainsString('Cadeaux secrets', $result);
        self::assertStringNotContainsString('Cadeau surprise', $result);
        $this->assertMercureUpdateCount(0);
    }

    private function rows(string $class): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->count([]);
    }

    private function reload(string $class, string $fixture): object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->find($this->getFixture($fixture)->getId());
    }

    public function testCreateTransactionWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(Transaction::class);

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('create', accountId: $this->id('checking'), amountCents: -1599, label: 'Boulangerie', bookedAt: '2026-07-08', categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(Transaction::class));
    }

    public function testCreateTransactionWithAnotherUsersAccountIsRefused(): void
    {
        $before = $this->rows(Transaction::class);

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('create', accountId: $this->id('other_checking'), amountCents: -1599, label: 'Boulangerie', bookedAt: '2026-07-08');

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(Transaction::class));
    }

    public function testUpdateTransactionWithAnotherUsersCategoryIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('update', transactionId: $this->id('groceries'), categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame('Alimentation', $this->reload(Transaction::class, 'groceries')->getCategory()->getName());
    }

    public function testUpdateTransactionWithAnotherUsersAccountIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('update', transactionId: $this->id('groceries'), accountId: $this->id('other_checking'));

        $this->assertRefused($result);
        self::assertSame('Compte courant', $this->reload(Transaction::class, 'groceries')->getAccount()->getName());
    }

    public function testCategorizeTransactionWithAnotherUsersCategoryIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('categorize', transactionId: $this->id('groceries'), categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame('Alimentation', $this->reload(Transaction::class, 'groceries')->getCategory()->getName());
    }

    public function testSetEnvelopeOnAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(Envelope::class);

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('set', categoryId: $this->id('other_secret'), amountCents: 15000, mode: 'monthly', year: 2026, month: 8);

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(Envelope::class));
    }

    public function testUpdateEnvelopeWithAnotherUsersCategoryIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('update', envelopeId: $this->id('food_july'), categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame('Alimentation', $this->reload(Envelope::class, 'food_july')->getCategory()->getName());
    }

    public function testCreateRuleWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(CategorizationRule::class);

        $tool = self::getContainer()->get(ManageCategorizationRulesTool::class);
        $result = $tool('create', labelPattern: 'BOULANGERIE', categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(CategorizationRule::class));
    }

    public function testUpdateRuleWithAnotherUsersCategoryIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageCategorizationRulesTool::class);
        $result = $tool('update', categorizationRuleId: $this->id('rule_carrefour'), categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame('Alimentation', $this->reload(CategorizationRule::class, 'rule_carrefour')->getCategory()->getName());
    }

    public function testLearnRuleFromAnotherUsersTransactionIsRefused(): void
    {
        $before = $this->rows(CategorizationRule::class);

        $tool = self::getContainer()->get(ManageCategorizationRulesTool::class);
        $result = $tool('learn', transactionId: $this->id('other_purchase'), categoryId: $this->id('food'));

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(CategorizationRule::class));
        self::assertSame('Cadeaux secrets', $this->reload(Transaction::class, 'other_purchase')->getCategory()->getName());
    }

    public function testLearnRuleWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(CategorizationRule::class);

        $tool = self::getContainer()->get(ManageCategorizationRulesTool::class);
        $result = $tool('learn', transactionId: $this->id('groceries'), categoryId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(CategorizationRule::class));
    }

    public function testCreateCategoryWithAnotherUsersParentIsRefused(): void
    {
        $before = $this->rows(Category::class);

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('create', name: 'Sous-catégorie', parentId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertSame($before, $this->rows(Category::class));
    }

    public function testUpdateCategoryWithAnotherUsersParentIsRefused(): void
    {
        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('update', categoryId: $this->id('leisure'), parentId: $this->id('other_secret'));

        $this->assertRefused($result);
        self::assertNull($this->reload(Category::class, 'leisure')->getParent());
    }
}
