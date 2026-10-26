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
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * MAG-648: an ingredient linked to Ciqual gets the micronutrients of the food linked, and only of
 * it — on creation, on update, and through a recipe line, as the macros do (MAG-647).
 */
class IngredientMicronutrientsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use FakesCiqualTrait;

    private const FAKE_FOOD = ['vitaminC' => 18.0, 'iron' => 0.3];

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

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get(EntityManagerInterface::class);
    }

    private function named(string $name): Ingredient
    {
        $this->em()->clear();

        return $this->em()->getRepository(Ingredient::class)->findOneBy(['name' => $name]);
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    public function testAnIngredientCreatedWithACiqualCodeGetsItsMicronutrients(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce', ciqualAlimCode: self::COURGETTE));

        self::assertSame(self::FAKE_FOOD, $this->named('Courgette')->getMicronutrientsPer100g(), 'A value Ciqual does not know (vitamin D) is absent, not 0');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testAnIngredientWithoutCiqualCodeHasNone(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Bœuf à braiser', category: 'meat'));

        self::assertNull($this->named('Bœuf à braiser')->getMicronutrientsPer100g());
    }

    public function testLinkingAnotherFoodReplacesThemAndUnlinkingRemovesThem(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce', ciqualAlimCode: self::COURGETTE));
        $courgette = $this->named('Courgette');
        $courgette->setMicronutrientsPer100g(['calcium' => 99.0]);
        $this->em()->flush();
        $id = (string) $courgette->getId();

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: $id, name: 'Courgette verte'));
        self::assertSame(['calcium' => 99.0], $this->named('Courgette verte')->getMicronutrientsPer100g(), 'An update that does not touch the link keeps them');

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: $id, ciqualAlimCode: '20021'));
        self::assertSame(self::FAKE_FOOD, $this->named('Courgette verte')->getMicronutrientsPer100g(), 'The food linked wins');

        $this->bus()->dispatch(new UpdateIngredientCommand(ingredientId: $id, clearFields: ['ciqualAlimCode']));
        self::assertNull($this->named('Courgette verte')->getMicronutrientsPer100g());
    }

    public function testARecipeLineFillsThemForAnIngredientWithMacrosButNoMicronutrients(): void
    {
        $this->bus()->dispatch(new CreateIngredientCommand(userId: $this->userId(), name: 'Courgette', category: 'produce', ciqualAlimCode: self::COURGETTE));
        $courgette = $this->named('Courgette');
        $courgette->setMicronutrientsPer100g(null);
        $courgette->setKcalPer100g(25.0);
        $this->em()->flush();

        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Gratin',
            ingredients: [['ingredientId' => (string) $courgette->getId(), 'quantity' => 300.0, 'unit' => 'g']],
        ));

        $courgette = $this->named('Courgette');
        self::assertSame(self::FAKE_FOOD, $courgette->getMicronutrientsPer100g());
        self::assertSame(25.0, $courgette->getKcalPer100g(), 'A macro already there stays');
    }

    public function testARecipeLineCreatingTheIngredientFromCiqualGivesThemToo(): void
    {
        $this->bus()->dispatch(new CreateRecipeCommand(
            userId: $this->userId(),
            name: 'Gratin',
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 300.0, 'unit' => 'g']],
        ));

        $this->em()->clear();
        $courgette = $this->em()->getRepository(Ingredient::class)->findOneBy(['ciqualAlimCode' => self::COURGETTE]);
        self::assertSame(self::FAKE_FOOD, $courgette->getMicronutrientsPer100g());
    }
}
