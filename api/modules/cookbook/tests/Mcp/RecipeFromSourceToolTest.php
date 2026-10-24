<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeStep;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Mcp\Tool\CreateRecipeTool;
use Maggie\Cookbook\Service\CiqualClient;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;

/**
 * MAG-598: what Maggie sends to create_recipe once the user said « oui » to a recipe she structured
 * from a page, a pasted text or a dictation — the same call as the e2e fake-llm scenario. Nothing is
 * deduced by the tool: ingredients come by Ciqual code, the steps name them by the exact Ciqual
 * name (the products are created by this very call), a utensil unknown to the catalogue is added.
 * Only Ciqual itself is faked.
 */
class RecipeFromSourceToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURES = __DIR__.'/../Api/fixtures/RecipeStepsTest.yaml';
    private const SOURCE = 'http://wiremock:8080/pages/marmiton/boeuf-bourguignon';

    public const CIQUAL = [
        '6270' => 'Boeuf, paleron cru',
        '28501' => 'Lardon nature, cru',
        '20034' => 'Oignon, cru',
        '20009' => 'Carotte, crue',
        '5214' => 'Vin rouge',
        '11000' => 'Ail, cru',
    ];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        self::getContainer()->set(CiqualClient::class, new class(new MockHttpClient()) extends CiqualClient {
            public function getFood(string $alimCode): ?array
            {
                $name = RecipeFromSourceToolTest::CIQUAL[$alimCode] ?? null;

                return null === $name ? null : [
                    'alim_code' => $alimCode,
                    'alim_name_fr' => $name,
                    'alim_group_code' => '02',
                    'alim_group_name_fr' => 'fruits, légumes, légumineuses et oléagineux',
                    'alim_ssgroup_code' => '0201',
                    'alim_ssgroup_name_fr' => 'légumes',
                    'nutrients' => [],
                ];
            }
        });
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    /** @return array<string, mixed> */
    private function createMarmitonRecipe(string $ingredients): array
    {
        $steps = [
            ['text' => 'Faire revenir les lardons puis les morceaux de boeuf dans une cocotte.', 'ingredientIds' => ['Lardon nature, cru', 'Boeuf, paleron cru'], 'utensils' => ['Cocotte']],
            ['text' => "Ajouter les oignons, les carottes et l'ail, puis mouiller avec le vin.", 'ingredientIds' => ['Oignon, cru', 'Carotte, crue', 'Ail, cru', 'Vin rouge'], 'waitsFor' => [1]],
            ['text' => 'Laisser mijoter à feu doux pendant 3 heures.', 'durationMinutes' => 180, 'background' => true, 'waitsFor' => [2]],
        ];

        return json_decode(self::getContainer()->get(CreateRecipeTool::class)(
            'Boeuf bourguignon : la vraie recette',
            servings: 6,
            notes: 'Source : '.self::SOURCE,
            ingredients: $ingredients,
            utensils: '["Cocotte"]',
            steps: json_encode($steps, JSON_THROW_ON_ERROR),
        ), true, 512, JSON_THROW_ON_ERROR);
    }

    private function marmitonIngredients(): string
    {
        return json_encode([
            ['ciqualAlimCode' => '6270', 'quantity' => 1.2, 'unit' => 'kg'],
            ['ciqualAlimCode' => '28501', 'quantity' => 150, 'unit' => 'g'],
            ['ciqualAlimCode' => '20034', 'quantity' => 2, 'unit' => 'piece'],
            ['ciqualAlimCode' => '20009', 'quantity' => 3, 'unit' => 'piece'],
            ['ciqualAlimCode' => '5214', 'quantity' => 75, 'unit' => 'cl'],
            ['ciqualAlimCode' => '11000', 'quantity' => 2, 'unit' => 'piece'],
        ], JSON_THROW_ON_ERROR);
    }

    public function testTheRecipeStructuredFromAPageHoldsEverythingInTheDatabase(): void
    {
        $data = $this->createMarmitonRecipe($this->marmitonIngredients());

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        $id = $data['recipe']['id'];
        $recipe = $this->em()->find(Recipe::class, $id);
        self::assertNotNull($recipe);

        self::assertSame(6, $recipe->getServings());
        self::assertSame('Source : '.self::SOURCE, $recipe->getNotes());
        self::assertEqualsCanonicalizing(
            array_values(self::CIQUAL),
            array_map(static fn ($line) => $line->getIngredient()->getName(), $recipe->getIngredients()->toArray()),
        );
        self::assertSame(
            [
                [1, null, false, ['Boeuf, paleron cru', 'Lardon nature, cru'], ['Cocotte'], []],
                [2, null, false, ['Ail, cru', 'Carotte, crue', 'Oignon, cru', 'Vin rouge'], [], [1]],
                [3, 180, true, [], [], [2]],
            ],
            array_map(static function (RecipeStep $step) {
                $ingredients = array_column($step->getIngredients(), 'name');
                sort($ingredients);

                return [$step->getPosition(), $step->getDurationMinutes(), $step->isBackground(), $ingredients, array_column($step->getUtensils(), 'name'), $step->getWaitsFor()];
            }, $recipe->getSteps()),
        );
        $this->assertMercureUpdatePublished('/api/recipes/'.$id);
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testAnUtensilUnknownToTheCatalogueIsAddedOnce(): void
    {
        self::assertNull($this->em()->getRepository(Utensil::class)->findOneBy(['name' => 'Cocotte']));

        $this->createMarmitonRecipe($this->marmitonIngredients());

        self::assertCount(1, $this->em()->getRepository(Utensil::class)->findBy(['name' => 'Cocotte']));
    }

    public function testAnUnknownCiqualCodeCreatesNoRecipe(): void
    {
        $before = $this->em()->getRepository(Recipe::class)->count([]);

        $data = $this->createMarmitonRecipe(json_encode([['ciqualAlimCode' => '00000', 'quantity' => 1, 'unit' => 'piece']], JSON_THROW_ON_ERROR));

        self::assertArrayHasKey('error', $data);
        self::assertSame($before, $this->em()->getRepository(Recipe::class)->count([]));
    }
}
