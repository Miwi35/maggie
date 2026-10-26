<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Mcp\Tool\CreateRecipeTool;
use Maggie\Cookbook\Mcp\Tool\ManageIngredientsTool;
use Maggie\Cookbook\Tests\MessageHandler\FakesCiqualTrait;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** MAG-647: Maggie links an ingredient to Ciqual and gives the weight of its piece when she writes the recipe. */
class RecipePieceWeightToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use FakesCiqualTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();
        $this->fakeCiqual();
    }

    private function courgette(): Ingredient
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Ingredient::class)->findOneBy(['ciqualAlimCode' => self::COURGETTE]);
    }

    public function testCreateRecipeWithAPieceIngredientStoresItsWeightAndItsCiqualValues(): void
    {
        $tool = self::getContainer()->get(CreateRecipeTool::class);
        $ingredients = json_encode([['ciqualAlimCode' => self::COURGETTE, 'quantity' => 2, 'unit' => 'piece', 'pieceWeightGrams' => 280]], JSON_THROW_ON_ERROR);

        $data = json_decode($tool('Courgettes farcies', 2, ingredients: $ingredients), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        $courgette = $this->courgette();
        self::assertSame(280.0, $courgette->getPieceWeightGrams());
        self::assertSame(1.2, $courgette->getProteinPer100g());
    }

    public function testCreateRecipeRefusesAPieceWeightThatIsNotAWeight(): void
    {
        $tool = self::getContainer()->get(CreateRecipeTool::class);
        $ingredients = json_encode([['ciqualAlimCode' => self::COURGETTE, 'quantity' => 2, 'unit' => 'piece', 'pieceWeightGrams' => -3]], JSON_THROW_ON_ERROR);

        $data = json_decode($tool('Courgettes farcies', 2, ingredients: $ingredients), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('pieceWeightGrams', $data['error']);
    }

    public function testManageIngredientsSetsThenClearsThePieceWeight(): void
    {
        $tool = self::getContainer()->get(ManageIngredientsTool::class);
        $created = json_decode($tool('create', name: 'Oignon rouge', category: 'produce', pieceWeightGrams: 90.0), true, 512, JSON_THROW_ON_ERROR);
        self::assertEquals(90.0, $created['ingredient']['pieceWeightGrams']);

        $cleared = json_decode($tool('update', ingredientId: $created['ingredient']['id'], clear: ['pieceWeightGrams']), true, 512, JSON_THROW_ON_ERROR);

        self::assertNull($cleared['ingredient']['pieceWeightGrams']);
    }
}
