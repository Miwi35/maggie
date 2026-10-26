<?php

namespace Maggie\Cookbook\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Service\CiqualClient;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\HttpClient\MockHttpClient;

/** MAG-647: the recipes made before the nutrition counted pieces get their weights and Ciqual links. */
class BackfillRecipeNutritionCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('BackfillRecipeNutritionCommandTest.yaml');

        // Ingredients as they were before the weights existed.
        foreach (['onion', 'bouquet', 'unused'] as $reference) {
            $this->getFixture($reference)->setPieceWeightGrams(null);
        }
        $this->em()->flush();

        self::getContainer()->set(CiqualClient::class, new class(new MockHttpClient()) extends CiqualClient {
            public function searchFoods(string $query, int $limit = 10): array
            {
                return 'Poudre de perlimpinpin' === $query ? [] : [['alim_code' => '11000', 'alim_name_fr' => 'Herbes aromatiques, fraîches']];
            }

            public function getFood(string $alimCode): ?array
            {
                return [
                    'alim_code' => $alimCode,
                    'alim_name_fr' => 'Herbes aromatiques, fraîches',
                    'nutrients' => [
                        ['const_code' => '328', 'value' => 40.0],
                        ['const_code' => '25000', 'value' => 3.0],
                        ['const_code' => '31000', 'value' => 5.0],
                        ['const_code' => '40000', 'value' => 1.0],
                    ],
                ];
            }
        });

        $this->tester = new CommandTester((new Application(self::$kernel))->find('app:cookbook:backfill-recipe-nutrition'));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get(EntityManagerInterface::class);
    }

    private function ingredient(string $name): Ingredient
    {
        $this->em()->clear();

        return $this->em()->getRepository(Ingredient::class)->findOneBy(['name' => $name]);
    }

    public function testDryRunListsTheChangesAndWritesNothing(): void
    {
        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        $display = $this->tester->getDisplay();
        self::assertStringContainsString('[dry-run]', $display);
        self::assertStringContainsString('Oignon', $display);
        self::assertStringContainsString('100 g per piece', $display);
        self::assertStringContainsString('11000 « Herbes aromatiques, fraîches »', preg_replace('/\s+/', ' ', $display));
        self::assertNull($this->ingredient('Oignon')->getPieceWeightGrams());
        self::assertNull($this->ingredient('Bouquet garni')->getCiqualAlimCode());
    }

    public function testCiqualLinksAreOnlyProposedUnlessAskedFor(): void
    {
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertNull($this->ingredient('Bouquet garni')->getCiqualAlimCode());
        self::assertSame(10.0, $this->ingredient('Bouquet garni')->getPieceWeightGrams(), 'The weight is written.');
        self::assertStringContainsString('Proposed Ciqual links', $this->tester->getDisplay());
        self::assertStringContainsString('Herbes aromatiques', $this->tester->getDisplay());
    }

    public function testItFillsTheMissingWeightsAndLinksWithoutOverwritingAnything(): void
    {
        $this->tester->execute(['--link-ciqual' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame(100.0, $this->ingredient('Oignon')->getPieceWeightGrams());
        self::assertSame(80.0, $this->ingredient('Carotte')->getPieceWeightGrams(), 'A weight already there is kept.');
        $bouquet = $this->ingredient('Bouquet garni');
        self::assertSame(10.0, $bouquet->getPieceWeightGrams());
        self::assertSame('11000', $bouquet->getCiqualAlimCode());
        self::assertSame(40.0, $bouquet->getKcalPer100g());
        self::assertNull($this->ingredient('Poudre de perlimpinpin')->getPieceWeightGrams(), 'No known weight: left for a hand to set.');
        self::assertNull($this->ingredient('Oignon doux')->getPieceWeightGrams(), 'An ingredient no recipe uses is not touched.');
        self::assertStringContainsString('to set by hand: Poudre de perlimpinpin', preg_replace('/\s+/', ' ', $this->tester->getDisplay()));
    }

    public function testRunningItAgainChangesNothing(): void
    {
        $this->tester->execute(['--link-ciqual' => true]);
        $this->tester->execute(['--link-ciqual' => true]);

        self::assertStringContainsString('0 ingredient(s)', $this->tester->getDisplay());
    }
}
