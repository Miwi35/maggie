<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Mcp\Tool\GenerateGroceryListTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GenerateGroceryListToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
    }

    public function testGenerateGroceryListReturnsSuccess(): void
    {
        $this->loadFixtures('user.yaml');

        $tool = self::getContainer()->get(GenerateGroceryListTool::class);
        $result = $tool('2026-03-19', '2026-03-21');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertArrayHasKey('groceryList', $data);
        self::assertArrayHasKey('totalItems', $data['groceryList']);

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }
}
