<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** MAG-648: the recipe answers the micronutrients of one serving, from its ingredients' Ciqual values. */
class RecipeMicronutrientsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('RecipeMicronutrientsApiTest.yaml');
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    /** @return array<string, array{key: string, label: string, group: string, amount: float|int, unit: string, referencePercent: ?int}> */
    private function micronutrientsOf(string $recipe): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', '/api/recipes/'.$this->getFixture($recipe)->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        return array_column($body['micronutrientsPerServing'], null, 'key');
    }

    public function testRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/recipes/'.$this->getFixture('soup')->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnUnknownRecipeIsNotFound(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', '/api/recipes/not-a-ulid', [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        self::assertResponseStatusCodeSame(404);
    }

    public function testOneServingIsTheSumOfTheCountedLinesByHand(): void
    {
        $micronutrients = $this->micronutrientsOf('soup');

        // Vitamin C: 2 oranges × 150 g × 50 mg/100 g = 150 mg, for 2 servings = 75 mg, 75 / 80 mg = 94 %.
        self::assertEqualsWithDelta(75.0, $micronutrients['vitaminC']['amount'], 0.001);
        self::assertSame('mg', $micronutrients['vitaminC']['unit']);
        self::assertSame('Vitamine C', $micronutrients['vitaminC']['label']);
        self::assertSame('vitamins', $micronutrients['vitaminC']['group']);
        self::assertSame(94, $micronutrients['vitaminC']['referencePercent']);

        // Iron: 300 g × 0.1 + 0.2 kg = 200 g × 3.3 = 0.3 + 6.6 = 6.9 mg, / 2 = 3.45 mg, / 14 mg = 25 %.
        self::assertEqualsWithDelta(3.45, $micronutrients['iron']['amount'], 0.001);
        self::assertSame('Fer', $micronutrients['iron']['label']);
        self::assertSame('minerals', $micronutrients['iron']['group']);
        self::assertSame(25, $micronutrients['iron']['referencePercent']);

        // Omega-3 ALA: 200 g × 0.1 + 5 cl = 50 g × 9 = 0.2 + 4.5 = 4.7 g, / 2 = 2.35 g, / 2.22 g (1 % of 2 000 kcal) = 106 %.
        self::assertEqualsWithDelta(2.35, $micronutrients['omega3Ala']['amount'], 0.001);
        self::assertSame('g', $micronutrients['omega3Ala']['unit']);
        self::assertSame('fattyAcids', $micronutrients['omega3Ala']['group']);
        self::assertSame(106, $micronutrients['omega3Ala']['referencePercent']);

        self::assertSame(['vitaminC', 'calcium', 'iron', 'omega3Ala', 'omega6'], array_keys($micronutrients), 'In the order of the sheet: vitamins, minerals, fatty acids');
    }

    public function testANutrientNoCountedLineGivesIsAbsentNotZero(): void
    {
        $micronutrients = $this->micronutrientsOf('soup');

        self::assertArrayNotHasKey('vitaminD', $micronutrients, 'Only the uncounted bunch of herbs gives vitamin D');
        self::assertArrayNotHasKey('vitaminB12', $micronutrients, 'No ingredient gives vitamin B12');
    }

    public function testARecipeWhoseIngredientsHaveNoMicronutrientsAnswersAnEmptyList(): void
    {
        self::assertSame([], $this->micronutrientsOf('plain'));
    }
}
