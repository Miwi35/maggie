<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * MAG-647: what a recipe's nutrition needs from its ingredients — the Ciqual values behind a
 * code, and a weight per piece — is filled when the recipe or the ingredient is written.
 */
class RecipeLineNutritionTest extends KernelTestCase
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

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }

    private function courgette(): Ingredient
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->clear();

        return $em->getRepository(Ingredient::class)->findOneBy(['ciqualAlimCode' => self::COURGETTE]);
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    public function testAPieceLineKeepsTheWeightMaggieGave(): void
    {
        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Gratin',
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 2.0, 'unit' => 'piece', 'pieceWeightGrams' => 310.0]],
        ));

        self::assertSame(310.0, $this->courgette()->getPieceWeightGrams());
    }

    public function testAPieceLineWithNoWeightFallsBackOnTheAverageWeightsTable(): void
    {
        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Gratin',
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 2.0, 'unit' => 'piece']],
        ));

        self::assertSame(250.0, $this->courgette()->getPieceWeightGrams());
    }

    public function testAWeightInGramsLeavesThePieceWeightEmptyForAnIngredientTheTableDoesNotKnow(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Bœuf à braiser', category: 'meat'));
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $beef = $em->getRepository(Ingredient::class)->findOneBy(['name' => 'Bœuf à braiser']);

        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Daube',
            ingredients: [['ingredientId' => (string) $beef->getId(), 'quantity' => 1000.0, 'unit' => 'g']],
        ));

        self::assertNull($beef->getPieceWeightGrams());
    }

    public function testAPieceWeightThatIsNotPositiveIsRefused(): void
    {
        $this->expectException(\Throwable::class);

        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Gratin',
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 2.0, 'unit' => 'piece', 'pieceWeightGrams' => 0.0]],
        ));
    }

    public function testAnIngredientCreatedWithOnlyACiqualCodeGetsItsMacros(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(
            userId: $this->userId(),
            name: 'Courgette',
            category: 'produce',
            ciqualAlimCode: self::COURGETTE,
        ));

        $courgette = $this->courgette();
        self::assertSame(19.0, $courgette->getKcalPer100g());
        self::assertSame(1.2, $courgette->getProteinPer100g());
        self::assertSame(2.3, $courgette->getCarbsPer100g());
        self::assertSame(0.4, $courgette->getFatPer100g());
    }

    public function testMacrosGivenByTheCallerAreNotReplacedByCiqualsAndNewOnesAreFilled(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(
            userId: $this->userId(),
            name: 'Courgette',
            category: 'produce',
            ciqualAlimCode: self::COURGETTE,
            kcalPer100g: 30.0,
        ));

        $courgette = $this->courgette();
        self::assertSame(30.0, $courgette->getKcalPer100g());
        self::assertSame(1.2, $courgette->getProteinPer100g());
    }

    public function testLinkingAnExistingIngredientToCiqualBringsTheMacros(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce'));
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $ingredient = $em->getRepository(Ingredient::class)->findOneBy(['name' => 'Courgette']);
        self::assertNull($ingredient->getKcalPer100g());

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: (string) $ingredient->getId(), ciqualAlimCode: self::COURGETTE));

        self::assertSame(19.0, $this->courgette()->getKcalPer100g());
    }

    public function testAMacroClearedOnPurposeIsNotRefilled(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce', ciqualAlimCode: self::COURGETTE));
        $ingredient = $this->courgette();

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: (string) $ingredient->getId(), clearFields: ['kcalPer100g']));

        self::assertNull($this->courgette()->getKcalPer100g());
        self::assertSame(1.2, $this->courgette()->getProteinPer100g());
    }

    public function testAMacroClearedEarlierStaysClearedThroughALaterUnrelatedUpdate(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce', ciqualAlimCode: self::COURGETTE));
        $id = (string) $this->courgette()->getId();
        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: $id, clearFields: ['kcalPer100g']));

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: $id, name: 'Courgette verte'));

        self::assertNull($this->courgette()->getKcalPer100g());
    }

    public function testALineCannotReachAnIngredientOfAnotherUser(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: (string) $this->getFixture('other_user')->getId(), name: 'Safran', category: 'other'));
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $foreign = $em->getRepository(Ingredient::class)->findOneBy(['name' => 'Safran']);

        $this->expectException(HandlerFailedException::class);
        try {
            $this->bus()->dispatch(new CreateRecipeCommand(
                userId: $this->userId(),
                name: 'Paella',
                ingredients: [['ingredientId' => (string) $foreign->getId(), 'quantity' => 1.0, 'unit' => 'g', 'pieceWeightGrams' => 3.0]],
            ));
        } finally {
            $em->clear();
            self::assertNull($em->getRepository(Ingredient::class)->findOneBy(['name' => 'Safran'])->getPieceWeightGrams());
        }
    }
}
