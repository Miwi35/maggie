<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Mcp\Tool\ManageMealSettingsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_meal_settings` (MAG-482): the default guests, read and written by Maggie. */
class MealSettingsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('meal.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, ?int $defaultGuests = null): array
    {
        $tool = self::getContainer()->get(ManageMealSettingsTool::class);

        return json_decode($tool($action, $defaultGuests), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItIsRefused(): void
    {
        $data = $this->call('get');

        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testGetAnswersTheDefaultOfTwo(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('get');

        self::assertSame(2, $data['defaultGuests']);
    }

    public function testUpdateStoresPublishesAndAnswersTheNewDefault(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', 4);

        self::assertTrue($data['success']);
        self::assertSame(4, $data['defaultGuests']);
        $stored = self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(MealSettings::class)->findAll();
        self::assertCount(1, $stored);
        self::assertSame(4, $stored[0]->getDefaultGuests());
        $this->assertMercureUpdatePublished('/api/meal_settings/'.$stored[0]->getId());
        $this->assertNoElasticsearchIndexDispatched(MealSettings::class);
    }

    public function testUpdateWithoutADefaultIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update');

        self::assertStringContainsString('needs defaultGuests', $data['error']);
        self::assertSame([], self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(MealSettings::class)->findAll());
    }

    public function testUpdateBelowOneIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', 0);

        self::assertStringContainsString('at least 1', $data['error']);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('reset')['error']);
    }
}
