<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateCategoryHandlerTest extends KernelTestCase
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

    private function dispatch(UpdateCategoryCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('concerts')->getId();
    }

    private function reload(): Category
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Category::class)->find($this->getFixture('concerts')->getId());
    }

    public function testClearingTheParentMakesItATopLevelCategory(): void
    {
        $this->dispatch(new UpdateCategoryCommand(
            userId: $this->userId(),
            categoryId: $this->id(),
            clearFields: ['parentId'],
        ));

        $category = $this->reload();
        self::assertNull($category->getParent());
        self::assertSame('Concerts', $category->getName());
        self::assertSame('#9C27B0', $category->getColor());
        self::assertSame('music', $category->getIcon());
        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testClearingColorAndIconKeepsTheParent(): void
    {
        $this->dispatch(new UpdateCategoryCommand(
            userId: $this->userId(),
            categoryId: $this->id(),
            clearFields: ['color', 'icon'],
        ));

        $category = $this->reload();
        self::assertNull($category->getColor());
        self::assertNull($category->getIcon());
        self::assertSame('Loisirs', $category->getParent()?->getName());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateCategoryCommand(
            userId: $this->userId(),
            categoryId: $this->id(),
            name: 'Festivals',
        ));

        $category = $this->reload();
        self::assertSame('Festivals', $category->getName());
        self::assertSame('#9C27B0', $category->getColor());
        self::assertSame('music', $category->getIcon());
        self::assertSame('Loisirs', $category->getParent()?->getName());
    }

    public function testUnknownCategoryFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Category not found');

        $this->dispatch(new UpdateCategoryCommand(userId: $this->userId(), categoryId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['color']));
    }
}
