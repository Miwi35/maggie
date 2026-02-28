<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Maggie\Cookbook\Mcp\Tool\AddRecurringGroceryTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class RecurringGroceryToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
    }

    public function testAddRecurringGroceryPersists(): void
    {
        $this->loadFixtures('user.yaml');

        $tool = self::getContainer()->get(AddRecurringGroceryTool::class);
        $result = $tool('weekly', customLabel: 'Pain de mie', quantity: 1, unit: 'pack');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Pain de mie', $data['recurringItem']['label']);
        self::assertSame('weekly', $data['recurringItem']['frequency']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $items = $em->getRepository(RecurringGroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Pain de mie', $items[0]->getLabel());
    }
}
