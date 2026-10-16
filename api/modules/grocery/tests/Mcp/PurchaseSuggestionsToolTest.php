<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Mcp\Tool\GetPurchaseSuggestionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * `get_purchase_suggestions` (MAG-495): the same suggestions as
 * `GET /api/grocery-suggestions`. A read: nothing is published.
 */
class PurchaseSuggestionsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-13 09:00:00', new \DateTimeZone('Europe/Paris'))));
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(): array
    {
        return json_decode(self::getContainer()->get(GetPurchaseSuggestionsTool::class)(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItIsAnError(): void
    {
        $data = $this->call();

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('suggestions', $data);
    }

    public function testTheCoffeeIsDue(): void
    {
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/PurchaseSuggestions.yaml');
        $this->loginFixtureUser();

        $data = $this->call();

        self::assertSame(1, $data['count']);
        self::assertSame((string) $this->getFixture('product_coffee')->getId(), $data['suggestions'][0]['product']['id']);
        self::assertSame('Café', $data['suggestions'][0]['product']['name']);
        self::assertSame(14, $data['suggestions'][0]['everyDays']);
        self::assertSame('2026-10-13', $data['suggestions'][0]['dueOn']);
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUserSeesOnlyTheirOwnHistory(): void
    {
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/PurchaseSuggestions.yaml');
        $this->loginFixtureUser('other_user');

        $data = $this->call();

        self::assertSame([(string) $this->getFixture('other_coffee')->getId()], array_column(array_column($data['suggestions'], 'product'), 'id'));
    }
}
