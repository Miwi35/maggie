<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Mcp\Tool\DeleteEventTool;
use Maggie\Calendar\Mcp\Tool\DeleteTaskTool;
use Maggie\Calendar\Mcp\Tool\ManageAgendasTool;
use Maggie\Cookbook\Mcp\Tool\DeleteRecipeTool;
use Maggie\Cookbook\Mcp\Tool\ManageIngredientsTool;
use Maggie\Cookbook\Mcp\Tool\ManageMealsTool;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Service\UserDataEraser;
use Maggie\Finance\Mcp\Tool\ManageAccountsTool;
use Maggie\Finance\Mcp\Tool\ManageCategoriesTool;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Maggie\Finance\Mcp\Tool\ManageLoansTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Grocery\Mcp\Tool\ManageProductsTool;
use Maggie\Grocery\Mcp\Tool\ManageRecurringGroceriesTool;
use Maggie\Grocery\Mcp\Tool\ManageStoresTool;
use Maggie\Notification\Mcp\Tool\ManageNotificationsTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * MAG-266: whatever deletes a row — REST, a Maggie tool, a cascade, the Recette
 * reset — the document has to leave Elasticsearch, or `status --check` fails
 * the deploy.
 *
 * The check is one invariant over a world with a row of every module: every
 * document whose row disappeared from the database (the entity and what the
 * database cascaded with it) is the target of a DeleteDocumentCommand sent to
 * the index the entity is served from, and no delete targets an index that does
 * not exist.
 */
final class DeletionReachesTheIndexTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use SecurityTokenTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(__DIR__.'/../Command/fixtures/RecetteResetCommandTest.yaml', 'DeletionReachesTheIndexTest.yaml');
        $this->resetAsyncTransport();
    }

    /**
     * @return iterable<string, array{string, string, class-string, array<string, string>|null}>
     *                                                                                           [fixture, REST collection, MCP tool, the tool's arguments (the id goes in the `%id%` slot), or null: the id is the only one]
     */
    public static function deletablePaths(): iterable
    {
        yield 'agenda' => ['agenda_other', 'agendas', ManageAgendasTool::class, ['action' => 'delete', 'agendaId' => '%id%']];
        yield 'event' => ['event_other', 'events', DeleteEventTool::class, null];
        yield 'task' => ['task_other', 'tasks', DeleteTaskTool::class, null];
        yield 'store' => ['store_other', 'stores', ManageStoresTool::class, ['action' => 'delete', 'storeId' => '%id%']];
        yield 'product' => ['product_free_other', 'products', ManageProductsTool::class, ['action' => 'delete', 'productId' => '%id%']];
        yield 'ingredient' => ['ingredient_free_other', 'ingredients', ManageIngredientsTool::class, ['action' => 'delete', 'ingredientId' => '%id%']];
        yield 'recurring grocery item' => ['recurring_other', 'recurring_grocery_items', ManageRecurringGroceriesTool::class, ['action' => 'delete', 'recurringItemId' => '%id%']];
        yield 'recipe' => ['recipe_other', 'recipes', DeleteRecipeTool::class, ['recipeId' => '%id%']];
        yield 'meal' => ['meal_other', 'meals', ManageMealsTool::class, ['action' => 'delete', 'mealId' => '%id%']];
        yield 'notification' => ['notification_other', 'notifications', ManageNotificationsTool::class, ['action' => 'delete', 'notificationId' => '%id%']];
        yield 'account' => ['account_other', 'accounts', ManageAccountsTool::class, ['action' => 'delete', 'accountId' => '%id%']];
        yield 'category' => ['category_other', 'categories', ManageCategoriesTool::class, ['action' => 'delete', 'categoryId' => '%id%']];
        yield 'sub-category' => ['subcategory_other', 'categories', ManageCategoriesTool::class, ['action' => 'delete', 'categoryId' => '%id%']];
        yield 'transaction' => ['transaction_other', 'transactions', ManageTransactionsTool::class, ['action' => 'delete', 'transactionId' => '%id%']];
        yield 'envelope' => ['envelope_other', 'envelopes', ManageEnvelopesTool::class, ['action' => 'delete', 'envelopeId' => '%id%']];
        yield 'categorization rule' => ['rule_other', 'categorization_rules', ManageCategorizationRulesTool::class, ['action' => 'delete', 'categorizationRuleId' => '%id%']];
        yield 'loan' => ['loan_other', 'loans', ManageLoansTool::class, ['action' => 'delete', 'loanId' => '%id%']];
    }

    /**
     * @return iterable<string, array{string, string}>
     */
    public static function restPaths(): iterable
    {
        foreach (self::deletablePaths() as $name => [$fixture, $collection]) {
            yield $name => [$fixture, $collection];
        }
    }

    #[DataProvider('restPaths')]
    public function testDeletingThroughTheApiRemovesTheDocuments(string $fixture, string $collection): void
    {
        $owner = $this->getFixture('user_other');
        self::assertInstanceOf(User::class, $owner);
        $this->authenticateAsUser($owner);
        $id = (string) $this->getFixture($fixture)->getId();
        $before = $this->documentsInTheDatabase();

        $this->client->request('DELETE', "/api/{$collection}/{$id}", [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        $this->assertEveryVanishedRowHasItsDocumentDeleted($before);
    }

    /**
     * @param class-string               $tool
     * @param array<string, string>|null $arguments
     */
    #[DataProvider('deletablePaths')]
    public function testDeletingThroughMaggieRemovesTheDocuments(string $fixture, string $collection, string $tool, ?array $arguments): void
    {
        $this->loginFixtureUser('user_other');
        $id = (string) $this->getFixture($fixture)->getId();
        $before = $this->documentsInTheDatabase();

        $arguments = null === $arguments ? [$id] : array_map(static fn (string $value) => '%id%' === $value ? $id : $value, $arguments);
        $answer = json_decode((self::getContainer()->get($tool))(...$arguments), true, 512, \JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('error', $answer);
        $this->assertEveryVanishedRowHasItsDocumentDeleted($before);
    }

    public function testErasingAUserRemovesTheDocumentsOfEveryModule(): void
    {
        $before = $this->documentsInTheDatabase();

        self::getContainer()->get(UserDataEraser::class)->erase($this->getFixture('user_recette'), false);

        $this->assertEveryVanishedRowHasItsDocumentDeleted($before);
    }

    /**
     * @return array<string, list<string>> index => ids of the rows the index is meant to hold
     */
    private function documentsInTheDatabase(): array
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->clear();

        $documents = [];
        foreach (self::getContainer()->get(IndexableEntityRegistry::class)->getAll() as $index => $class) {
            $documents[$index] = array_map(
                static fn (array $row) => (string) $row['id'],
                $em->createQueryBuilder()->select('e.id')->from($class, 'e')->getQuery()->getArrayResult(),
            );
        }

        return $documents;
    }

    /**
     * @param array<string, list<string>> $before
     */
    private function assertEveryVanishedRowHasItsDocumentDeleted(array $before): void
    {
        $after = $this->documentsInTheDatabase();

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[$message->indexName][] = $message->documentId;
            }
        }

        $registered = array_keys($before);
        self::assertSame([], array_values(array_diff(array_keys($deleted), $registered)), 'A delete targets an index that does not exist.');

        $left = [];
        foreach ($before as $index => $ids) {
            foreach (array_diff($ids, $after[$index]) as $id) {
                if (!\in_array($id, $deleted[$index] ?? [], true)) {
                    $left[] = "{$index}/{$id}";
                }
            }
        }
        self::assertSame([], $left, 'The row is gone, its document is still in the index.');
    }
}
