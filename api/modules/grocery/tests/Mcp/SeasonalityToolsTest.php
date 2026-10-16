<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Mcp\Tool\GetSeasonalProduceTool;
use Maggie\Grocery\Mcp\Tool\ManageProductsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `get_seasonal_produce` and the season months of `manage_products` (MAG-494). */
class SeasonalityToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-16 12:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/SeasonalityApiTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function seasonal(?int $month = null): array
    {
        $tool = self::getContainer()->get(GetSeasonalProduceTool::class);

        return json_decode($tool($month), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $arguments
     *
     * @return array<string, mixed>
     */
    private function manage(string $action, array $arguments = []): array
    {
        $tool = self::getContainer()->get(ManageProductsTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(string $id): Product
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Product::class)->find($id);
    }

    public function testSeasonalProduceWithoutUserIsRefused(): void
    {
        $data = $this->seasonal();

        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testSeasonalProduceRefusesAMonthOutsideOneToTwelve(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->seasonal(13));
        self::assertArrayHasKey('error', $this->seasonal(0));
    }

    public function testSeasonalProduceDefaultsToTheCurrentMonth(): void
    {
        $this->loginFixtureUser();

        $data = $this->seasonal();

        self::assertSame(10, $data['month']);
        self::assertSame('octobre', $data['monthName']);
        $produce = array_column($data['produce'], 'name');
        self::assertContains('Potiron', $produce);
        self::assertNotContains('Tomate', $produce);

        $mine = array_column($data['userProducts'], 'name');
        self::assertContains('Potiron', $mine);
        self::assertContains('Courgette', $mine, 'Corrected to all year long by hand');
        self::assertNotContains('Tomates', $mine);
        self::assertNotContains('Lessive', $mine);
    }

    public function testSeasonalProduceListsOnlyTheUsersOwnProducts(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $other = (new User())->setEmail('season-other@example.com')->setGoogleId('google-season-other')->setName('Other');
        $theirs = (new Product())->setUser($other)->setName('Potiron du voisin')->setCategory(ProductCategory::Produce)->setSeasonMonths([10]);
        $em->persist($other);
        $em->persist($theirs);
        $em->flush();
        $this->loginFixtureUser();

        $mine = array_column($this->seasonal()['userProducts'], 'name');

        self::assertContains('Potiron', $mine);
        self::assertNotContains('Potiron du voisin', $mine);
    }

    public function testSeasonalProduceForAGivenMonth(): void
    {
        $this->loginFixtureUser();

        $data = $this->seasonal(7);

        self::assertSame(7, $data['month']);
        self::assertContains('Tomate', array_column($data['produce'], 'name'));
        self::assertContains('Tomates', array_column($data['userProducts'], 'name'));
        self::assertNotContains('Potiron', array_column($data['userProducts'], 'name'));
    }

    public function testManageProductsWithoutUserIsRefused(): void
    {
        $data = $this->manage('create', ['name' => 'Potiron', 'category' => 'produce']);

        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testCreatePrefillsTheSeasonAndSaysItIsInSeason(): void
    {
        $this->loginFixtureUser();

        $data = $this->manage('create', ['name' => 'Potiron', 'category' => 'produce']);

        self::assertTrue($data['success']);
        self::assertSame([1, 9, 10, 11, 12], $data['product']['seasonMonths']);
        self::assertTrue($data['product']['inSeason']);
        self::assertSame([1, 9, 10, 11, 12], $this->reload($data['product']['id'])->getSeasonMonths());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testCreateOfAProductWithoutSeason(): void
    {
        $this->loginFixtureUser();

        $data = $this->manage('create', ['name' => 'Lessive', 'category' => 'cleaning']);

        self::assertNull($data['product']['seasonMonths']);
        self::assertNull($data['product']['inSeason']);
    }

    public function testUpdateCorrectsTheSeasonMonths(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('tomates')->getId();

        $data = $this->manage('update', ['productId' => $id, 'seasonMonths' => [10, 7, 8, 9]]);

        self::assertTrue($data['success']);
        self::assertSame([7, 8, 9, 10], $data['product']['seasonMonths']);
        self::assertTrue($data['product']['inSeason']);
        self::assertSame([7, 8, 9, 10], $this->reload($id)->getSeasonMonths());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testUpdateRefusesAMonthOutsideOneToTwelve(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('tomates')->getId();

        $data = $this->manage('update', ['productId' => $id, 'seasonMonths' => [6, 13]]);

        self::assertStringContainsString('seasonMonths', $data['error']);
        self::assertSame([6, 7, 8, 9], $this->reload($id)->getSeasonMonths());
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateClearsTheSeason(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('tomates')->getId();

        $data = $this->manage('update', ['productId' => $id, 'clear' => ['seasonMonths']]);

        self::assertNull($data['product']['seasonMonths']);
        self::assertNull($data['product']['inSeason']);
        self::assertNull($this->reload($id)->getSeasonMonths());
    }
}
