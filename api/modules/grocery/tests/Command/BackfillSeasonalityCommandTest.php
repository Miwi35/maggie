<?php

namespace Maggie\Grocery\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Product;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/** `app:grocery:seasonality:backfill` (MAG-494): fills what has no months, never what the user typed. */
class BackfillSeasonalityCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/SeasonalityApiTest.yaml');

        // Products created before the seasonality: no months at all.
        $this->em->getConnection()->executeStatement('UPDATE product SET season_months = NULL WHERE name <> :corrected', ['corrected' => 'Courgette']);
        $this->em->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function backfill(string ...$options): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find('app:grocery:seasonality:backfill'));
        $tester->execute(array_fill_keys($options, true));

        return $tester;
    }

    /** @return list<int>|null */
    private function monthsOf(string $name): ?array
    {
        $this->em->clear();

        return $this->em->getRepository(Product::class)->findOneBy(['name' => $name])?->getSeasonMonths();
    }

    public function testFillsTheProductsTheCalendarKnowsAndPublishesThem(): void
    {
        $tester = $this->backfill();

        $tester->assertCommandIsSuccessful();
        self::assertSame([1, 9, 10, 11, 12], $this->monthsOf('Potiron'));
        self::assertSame([6, 7, 8, 9], $this->monthsOf('Tomates'));
        self::assertNull($this->monthsOf('Lessive'));

        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testNeverOverwritesAValueCorrectedByHand(): void
    {
        $this->backfill();

        self::assertSame(range(1, 12), $this->monthsOf('Courgette'));
    }

    public function testDryRunChangesNothing(): void
    {
        $tester = $this->backfill('--dry-run');

        $tester->assertCommandIsSuccessful();
        self::assertStringContainsString('2 product(s) would get', $tester->getDisplay());
        self::assertNull($this->monthsOf('Potiron'));
        $this->assertMercureUpdateCount(0);
    }
}
