<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Mcp\Tool\ReplaceRecipeIngredientTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** replace_recipe_ingredient: the steps follow the new product, their text only on request (MAG-601). */
class ReplaceRecipeIngredientToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function load(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/ReplaceRecipeIngredientTest.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->em();
    }

    /** @return array<string, mixed> */
    private function call(mixed ...$args): array
    {
        return json_decode(self::getContainer()->get(ReplaceRecipeIngredientTool::class)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<int, array{text: string, ingredients: list<string>}> */
    private function steps(): array
    {
        $recipe = $this->em()->find(Recipe::class, $this->id('bourguignon'));
        self::assertNotNull($recipe);
        $steps = [];
        foreach ($recipe->getSteps() as $step) {
            $steps[$step->getPosition()] = ['text' => $step->getText(), 'ingredients' => array_column($step->getIngredients(), 'name')];
        }

        return $steps;
    }

    public function testTheStepsFollowAndTheTextIsOnlySuggested(): void
    {
        $this->load();

        $data = $this->call($this->id('bourguignon'), $this->id('bacon_food'), withIngredientId: $this->id('belly_food'));

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        self::assertFalse($data['textRewritten']);
        self::assertSame([2, 6], array_column($data['textSuggestions'], 'position'));
        $steps = $this->steps();
        self::assertSame(['Poitrine fumée'], $steps[2]['ingredients']);
        self::assertSame(['Poitrine fumée'], $steps[6]['ingredients']);
        self::assertSame('Parsemer de lardons fumés et servir', $steps[6]['text']);
        $this->assertMercureUpdatePublished('/api/recipes/'.$this->id('bourguignon'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $this->id('bourguignon'));
    }

    public function testRewriteTextChangesTheTexts(): void
    {
        $this->load();

        $data = $this->call($this->id('bourguignon'), $this->id('bacon_food'), withIngredientId: $this->id('belly_food'), rewriteText: true);

        self::assertTrue($data['textRewritten']);
        self::assertSame('Parsemer de poitrine fumée et servir', $this->steps()[6]['text']);
    }

    public function testBadCallsAreRefusedAndNothingIsWritten(): void
    {
        $this->load();
        $before = $this->steps();

        self::assertArrayHasKey('error', $this->call($this->id('bourguignon'), $this->id('bacon_food')));
        self::assertArrayHasKey('error', $this->call($this->id('bourguignon'), $this->id('bacon_food'), withIngredientId: $this->id('belly_food'), withCiqualAlimCode: '1000'));
        self::assertArrayHasKey('error', $this->call($this->id('bourguignon'), $this->id('belly_food'), withIngredientId: $this->id('bacon_food')));
        self::assertArrayHasKey('error', $this->call($this->id('bourguignon'), $this->id('bacon_food'), withIngredientId: $this->id('other_food')));
        self::assertArrayHasKey('error', $this->call($this->id('bourguignon'), $this->id('bacon_food'), withIngredientId: $this->id('belly_food'), quantity: -1.0));
        self::assertArrayHasKey('error', $this->call($this->id('other_recipe'), $this->id('bacon_food'), withIngredientId: $this->id('belly_food')));
        self::assertSame($before, $this->steps());
    }
}
