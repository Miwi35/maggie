<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * MAG-191: resolving a Ciqual code looked the user up with an untyped ULID
 * parameter, which Postgres refuses for a `uuid` column — every recipe built
 * from a Ciqual code answered 500. Only Ciqual itself is faked here; the
 * repository query runs against the real database.
 */
class CreateRecipeHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use FakesCiqualTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('CreateRecipeHandlerTest.yaml');

        $this->fakeCiqual();
    }

    private function createRecipe(string $name, string $userFixture = 'test_user'): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateRecipeCommand(
            userId: (string) $this->getFixture($userFixture)->getId(),
            name: $name,
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 300.0, 'unit' => 'g']],
        ));
    }

    /** @return Ingredient[] */
    private function courgettes(): array
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->clear();

        return $em->getRepository(Ingredient::class)->findBy(['ciqualAlimCode' => self::COURGETTE]);
    }

    public function testARecipeBuiltFromACiqualCodeCreatesTheIngredientWithItsMacros(): void
    {
        $this->createRecipe('Gratin de courgettes');

        $courgettes = $this->courgettes();
        self::assertCount(1, $courgettes);
        self::assertSame('Courgette, crue', $courgettes[0]->getName());
        self::assertSame(19.0, $courgettes[0]->getKcalPer100g());
        self::assertSame(1.2, $courgettes[0]->getProteinPer100g());
        self::assertSame(2.3, $courgettes[0]->getCarbsPer100g());
        self::assertSame(0.4, $courgettes[0]->getFatPer100g());
        self::assertSame((string) $this->getFixture('test_user')->getId(), (string) $courgettes[0]->getUser()->getId());

        $em = self::getContainer()->get(EntityManagerInterface::class);
        $recipe = $em->getRepository(Recipe::class)->findOneBy(['name' => 'Gratin de courgettes']);
        self::assertCount(1, $recipe->getIngredients());
        self::assertSame($courgettes[0]->getId()->toRfc4122(), $recipe->getIngredients()->first()->getIngredient()->getId()->toRfc4122());
    }

    public function testASecondRecipeWithTheSameCodeReusesTheIngredient(): void
    {
        $this->createRecipe('Gratin de courgettes');
        $this->createRecipe('Courgettes sautées');

        self::assertCount(1, $this->courgettes());
    }

    public function testAnotherUserGetsHisOwnIngredientForTheSameCode(): void
    {
        $this->createRecipe('Gratin de courgettes');
        $this->createRecipe('Courgettes sautées', 'other_user');

        self::assertCount(2, $this->courgettes());
    }

    public function testTheIngredientCreatedOnTheWayIsIndexedAndPublished(): void
    {
        $this->createRecipe('Gratin de courgettes');

        $this->assertElasticsearchIndexDispatched(Ingredient::class);
        $this->assertMercureUpdatePublished('/ingredients/'.$this->courgettes()[0]->getId());
    }

    public function testAnIngredientReusedByARecipeIsNotBroadcastAgain(): void
    {
        $this->createRecipe('Gratin de courgettes');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->createRecipe('Courgettes sautées');

        $this->assertMercureUpdateCount(1);
    }
}
