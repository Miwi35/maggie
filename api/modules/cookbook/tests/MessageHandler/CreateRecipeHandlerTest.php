<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Service\CiqualClient;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
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

    private const COURGETTE = '20020';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('CreateRecipeHandlerTest.yaml');

        self::getContainer()->set(CiqualClient::class, new class(new MockHttpClient()) extends CiqualClient {
            public function getFood(string $alimCode): ?array
            {
                return [
                    'alim_code' => $alimCode,
                    'alim_name_fr' => 'Courgette, crue',
                    'alim_group_code' => '02',
                    'alim_group_name_fr' => 'fruits, légumes, légumineuses et oléagineux',
                    'alim_ssgroup_code' => '0201',
                    'alim_ssgroup_name_fr' => 'légumes',
                    'nutrients' => [
                        ['const_code' => '328', 'const_name_fr' => 'Energie', 'const_unit' => 'kcal/100 g', 'value' => 19.0, 'confidence_code' => 'A', 'raw_value' => '19'],
                        ['const_code' => '25000', 'const_name_fr' => 'Protéines', 'const_unit' => 'g/100 g', 'value' => 1.2, 'confidence_code' => 'A', 'raw_value' => '1,2'],
                        ['const_code' => '31000', 'const_name_fr' => 'Glucides', 'const_unit' => 'g/100 g', 'value' => 2.3, 'confidence_code' => 'A', 'raw_value' => '2,3'],
                        ['const_code' => '40000', 'const_name_fr' => 'Lipides', 'const_unit' => 'g/100 g', 'value' => 0.4, 'confidence_code' => 'A', 'raw_value' => '0,4'],
                    ],
                ];
            }
        });
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
}
