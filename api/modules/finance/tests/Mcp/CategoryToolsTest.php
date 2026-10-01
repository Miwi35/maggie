<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Mcp\Tool\ManageCategoriesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CategoryToolsTest extends KernelTestCase
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

    public function testCreateCategoryPersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('create', name: 'Alimentation', obligation: 'mandatory', color: '#4CAF50');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Alimentation', $data['category']['name']);
        self::assertSame('mandatory', $data['category']['obligation']);
        self::assertNull($data['category']['parentId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $categories = $em->getRepository(Category::class)->findAll();
        self::assertCount(1, $categories);

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testCreateSubCategoryUnderParent(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        $parent = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('create', name: 'Restaurants', obligation: 'optional', parentId: (string) $parent->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame((string) $parent->getId(), $data['category']['parentId']);
    }

    public function testCreateThirdLevelIsRejected(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        // 'leisure_concerts' is already a sub-category (has a parent)
        $sub = $this->getFixture('leisure_concerts');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('create', name: 'Trop profond', parentId: (string) $sub->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('two levels', $data['error']);
    }

    public function testListCategoriesReturnsAll(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(3, $data['categories']);
    }

    public function testUpdateCategoryUpdatesAndPublishes(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('update', categoryId: (string) $category->getId(), name: 'Courses', obligation: 'mandatory');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Courses', $data['category']['name']);
        self::assertSame('mandatory', $data['category']['obligation']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Category::class, $category->getId());
        self::assertSame('Courses', $refreshed->getName());

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testClearMakesASubCategoryTopLevel(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        $concerts = $this->getFixture('leisure_concerts');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('update', categoryId: (string) $concerts->getId(), clear: ['parentId']);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertNull($data['category']['parentId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Category::class, $concerts->getId());
        self::assertNull($refreshed->getParent());
        self::assertSame('Concerts', $refreshed->getName());

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testClearEmptiesTheColorButNotTheName(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        $food = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('update', categoryId: (string) $food->getId(), clear: ['color', 'icon', 'name']);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Category::class, $food->getId());
        self::assertNull($refreshed->getColor());
        self::assertNull($refreshed->getIcon());
        self::assertSame('Alimentation', $refreshed->getName(), 'Required fields cannot be cleared');
        self::assertSame('mandatory', $refreshed->getObligation()->value);
    }

    public function testClearOnAnUnknownCategoryReturnsAnError(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $data = json_decode($tool('update', categoryId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['color']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testDeleteCategoryRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('category.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));
        $category = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('delete', categoryId: (string) $category->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Category::class, $category->getId()));

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchDeleteDispatched('categories');
    }

    public function testDeleteCategoryRemovesWhatItCascadesToFromTheIndex(): void
    {
        $this->loadFixtures('category_cascade.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $data = json_decode(
            $tool('delete', categoryId: (string) $this->getFixture('food')->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
        self::assertTrue($data['success']);

        $deleted = $this->deletedDocuments();
        self::assertContains(['categories', (string) $this->getFixture('restaurants')->getId()], $deleted);
        self::assertContains(['envelopes', (string) $this->getFixture('food_july')->getId()], $deleted);
        self::assertContains(['categorization_rules', (string) $this->getFixture('rule_restaurant')->getId()], $deleted);
    }

    /** @return array<int, array{string, string}> */
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
