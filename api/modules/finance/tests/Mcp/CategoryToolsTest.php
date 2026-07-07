<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
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

        $tool = self::getContainer()->get(ManageCategoriesTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(3, $data['categories']);
    }

    public function testUpdateCategoryUpdatesAndPublishes(): void
    {
        $this->loadFixtures('category.yaml');
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

    public function testDeleteCategoryRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('category.yaml');
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
}
