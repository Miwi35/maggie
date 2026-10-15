<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Mcp\Tool\EndErrandTool;
use Maggie\Grocery\Mcp\Tool\ListPastErrandsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `end_errand` archives, `list_past_errands` reads the archive back (MAG-488). */
class ErrandToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(object $tool, mixed ...$arguments): array
    {
        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testListingWithoutAUserIsAnError(): void
    {
        $data = $this->call(self::getContainer()->get(ListPastErrandsTool::class));

        self::assertArrayHasKey('error', $data);
    }

    public function testALimitOutOfRangeIsAnError(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ListPastErrandsTool::class);

        self::assertStringContainsString('limit', $this->call($tool, limit: 0)['error']);
        self::assertStringContainsString('limit', $this->call($tool, limit: 21)['error']);
    }

    public function testEndErrandArchivesWhatListPastErrandsReadsBack(): void
    {
        $this->loadFixtures('errands.yaml');
        $this->loginFixtureUser();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $ended = $this->call(self::getContainer()->get(EndErrandTool::class));

        self::assertSame(1, $ended['remainingCount']);
        self::assertCount(1, $ended['errands']);
        $errandId = $ended['errands'][0]['id'];
        self::assertSame(['storeName' => 'Carrefour Market', 'itemCount' => 1], array_diff_key($ended['errands'][0], ['id' => true]));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $errand = $em->find(Errand::class, $errandId);
        self::assertNotNull($errand);
        self::assertSame(['Lait'], array_map(static fn ($item) => $item->getLabel(), $errand->getItems()->toArray()));
        $this->assertMercureUpdatePublished('/api/errands/'.$errandId);
        $this->assertElasticsearchIndexDispatchedFor(Errand::class, $errandId);

        // « la dernière fois chez Carrefour »
        $listed = $this->call(self::getContainer()->get(ListPastErrandsTool::class), store: 'carrefour', limit: 1);

        self::assertSame(1, $listed['count']);
        self::assertSame($errandId, $listed['errands'][0]['id']);
        self::assertSame('Carrefour Market', $listed['errands'][0]['storeName']);
        self::assertSame(
            ['product' => null, 'label' => 'Lait', 'quantity' => 1, 'unit' => 'l', 'packagingUnit' => null, 'packagingSize' => null, 'packagingSizeUnit' => null],
            array_diff_key($listed['errands'][0]['items'][0], ['id' => true]),
        );

        $none = $this->call(self::getContainer()->get(ListPastErrandsTool::class), store: 'Lidl');
        self::assertSame(['errands' => [], 'count' => 0], $none);
    }

    public function testAnotherUsersErrandsAreNotListed(): void
    {
        $this->loadFixtures('errands.yaml');
        $this->loginFixtureUser();
        self::getContainer()->get(EndErrandTool::class)();

        $this->loginFixtureUser('other_user');

        self::assertSame(['errands' => [], 'count' => 0], $this->call(self::getContainer()->get(ListPastErrandsTool::class)));
    }
}
