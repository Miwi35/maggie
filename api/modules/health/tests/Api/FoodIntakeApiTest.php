<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Enum\FoodIntakeSource;
use Maggie\Health\Port\CiqualFood;
use Maggie\Health\Port\FoodCatalogInterface;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The food journal through the API Platform resource, « Mangé » included (MAG-748). */
class FoodIntakeApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private const ROWS = 2;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures('food_intake.yaml');
        // The fixtures' recipes do not know their lines until they are read back.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
        $this->resetMercure();
        self::getContainer()->set(FoodCatalogInterface::class, new class implements FoodCatalogInterface {
            public function food(string $code): ?CiqualFood
            {
                return '20020' === $code ? new CiqualFood('20020', 'Courgette, crue', 19.0, 1.2, 2.3, 0.4) : null;
            }
        });
    }

    private function login(string $fixture = 'test_user'): void
    {
        $user = $this->getFixture($fixture);
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed>|null $body */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function rows(): int
    {
        return $this->em()->getRepository(FoodIntake::class)->count([]);
    }

    /** @return array<string, mixed> */
    private function balance(string $date = '2026-11-05'): array
    {
        $this->send('GET', '/api/health/daily-balance?date='.$date);
        self::assertResponseIsSuccessful();

        return $this->response();
    }

    public function testCollectionRequiresAuthentication(): void
    {
        $this->send('GET', '/api/health/food-intakes', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(self::ROWS, $this->rows());
    }

    public function testTheCollectionListsOnlyTheUsersIntakesFilteredByDay(): void
    {
        $this->login();

        $this->send('GET', '/api/health/food-intakes');
        self::assertResponseIsSuccessful();
        self::assertSame([$this->id('apple_4')], array_column($this->response()['member'], 'id'));

        $this->send('GET', '/api/health/food-intakes?date[after]=2026-11-05');
        self::assertSame([], $this->response()['member']);
    }

    public function testTheShapeIsTheContract(): void
    {
        $this->login();

        $this->send('GET', '/api/health/food-intakes/'.$this->id('apple_4'));

        self::assertResponseIsSuccessful();
        $data = $this->response();
        self::assertSame('2026-11-04', $data['date']);
        self::assertSame('Pomme', $data['label']);
        self::assertSame(150, (int) $data['quantityGrams']);
        self::assertSame(78, $data['kcal']);
        self::assertSame(18, (int) $data['carbsG']);
        self::assertSame('manual', $data['source']);
        self::assertNull($data['mealId'] ?? null);
    }

    public function testAnotherUsersIntakeIsNotFound(): void
    {
        $this->login();

        $this->send('GET', '/api/health/food-intakes/'.$this->id('others_snack_5'));
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', '/api/health/food-intakes/'.$this->id('others_snack_5'));
        self::assertResponseStatusCodeSame(404);
        self::assertSame(self::ROWS, $this->rows());
    }

    /** The ticket's first criterion: a planned 515 kcal meal moves from planned to eaten. */
    public function testEatenMovesTheMealFromPlannedToEaten(): void
    {
        $this->login();

        $before = $this->balance();
        self::assertSame(515, $before['plannedKcal']);
        self::assertSame(0, $before['eatenKcal']);

        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')]);

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('meal', $data['source']);
        self::assertSame('2026-11-05', $data['date']);
        self::assertSame('Bol du midi', $data['label']);

        $intake = $this->em()->getRepository(FoodIntake::class)->find($data['id']);
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame($this->id('lunch_5'), $intake->getMealId());
        self::assertSame(515, $intake->getKcal());
        self::assertSame(20.0, $intake->getProteinG());
        self::assertSame(50.0, $intake->getCarbsG());
        self::assertSame(25.0, $intake->getFatG());
        self::assertSame(FoodIntakeSource::Meal, $intake->getSource());
        self::assertSame($this->id('test_user'), (string) $intake->getUser()->getId());
        $this->assertMercureUpdatePublished('/api/health/food-intakes/'.$intake->getId());

        $after = $this->balance();
        self::assertSame(0, $after['plannedKcal']);
        self::assertSame(515, $after['eatenKcal']);
        self::assertSame([$this->id('dinner_5')], array_column($after['plannedMeals'], 'mealId'));
    }

    public function testEatingTheSameMealTwiceIsRefusedWithoutASecondIntake(): void
    {
        $this->login();
        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')]);
        self::assertResponseStatusCodeSame(201);

        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('already eaten', (string) $this->client->getResponse()->getContent());
        self::assertSame(self::ROWS + 1, $this->rows());
    }

    public function testAnotherUsersMealOrAMealWithoutFiguresCannotBeEaten(): void
    {
        $this->login();

        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('others_lunch_5')]);
        self::assertResponseStatusCodeSame(400);

        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('dinner_5')]);
        self::assertResponseStatusCodeSame(400);

        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    /** Values are copied when eaten: deleting the meal afterwards keeps the intake and its figures. */
    public function testDeletingAnEatenMealKeepsTheIntake(): void
    {
        $this->login();
        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')]);
        $id = $this->response()['id'];

        $em = $this->em();
        $meal = $em->find(\Maggie\Cookbook\Entity\Meal::class, $this->id('lunch_5'));
        self::assertNotNull($meal);
        $em->remove($meal);
        $em->flush();

        $intake = $this->em()->getRepository(FoodIntake::class)->find($id);
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame(515, $intake->getKcal());
        self::assertSame(515, $this->balance()['eatenKcal']);
    }

    public function testAFoodTypedByHandCountsForTheDayOfItsMoment(): void
    {
        $this->login();

        $this->send('POST', '/api/health/food-intakes', [
            'eatenAt' => '2026-11-05T23:30:00+01:00',
            'label' => 'Tisane et biscuits',
            'kcal' => 180,
            'carbsG' => 30.0,
        ]);

        self::assertResponseStatusCodeSame(201);
        $intake = $this->em()->getRepository(FoodIntake::class)->find($this->response()['id']);
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame('2026-11-05', $intake->getDate()?->format('Y-m-d'));
        self::assertSame(180, $intake->getKcal());
        self::assertSame(30.0, $intake->getCarbsG());
        self::assertSame(0.0, $intake->getProteinG());
        self::assertSame(FoodIntakeSource::Manual, $intake->getSource());
        $this->assertMercureUpdatePublished('/api/health/food-intakes/'.$intake->getId());
    }

    public function testACiqualFoodWithItsGramsGetsItsFiguresFromCiqual(): void
    {
        $this->login();

        $this->send('POST', '/api/health/food-intakes', [
            'eatenAt' => '2026-11-05T12:30:00+01:00',
            'ciqualCode' => '20020',
            'quantityGrams' => 200.0,
        ]);

        self::assertResponseStatusCodeSame(201);
        $intake = $this->em()->getRepository(FoodIntake::class)->find($this->response()['id']);
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame('Courgette, crue', $intake->getLabel());
        self::assertSame('20020', $intake->getCiqualCode());
        self::assertSame(38, $intake->getKcal());
        self::assertSame(2.4, $intake->getProteinG());
        self::assertSame(4.6, $intake->getCarbsG());
        self::assertSame(0.8, $intake->getFatG());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function refusedBodies(): iterable
    {
        yield 'no moment' => [['label' => 'Pomme', 'kcal' => 52]];
        yield 'a day without a time' => [['eatenAt' => '2026-11-05', 'label' => 'Pomme', 'kcal' => 52]];
        yield 'no label' => [['eatenAt' => '2026-11-05T12:00:00+01:00', 'kcal' => 52]];
        yield 'no kcal' => [['eatenAt' => '2026-11-05T12:00:00+01:00', 'label' => 'Pomme']];
        yield 'negative kcal' => [['eatenAt' => '2026-11-05T12:00:00+01:00', 'label' => 'Pomme', 'kcal' => -5]];
        yield 'an unknown Ciqual food' => [['eatenAt' => '2026-11-05T12:00:00+01:00', 'ciqualCode' => '99999', 'quantityGrams' => 100.0]];
        yield 'a Ciqual food without grams' => [['eatenAt' => '2026-11-05T12:00:00+01:00', 'ciqualCode' => '20020']];
        yield 'an unknown meal' => [['mealId' => '01JZZZZZZZZZZZZZZZZZZZZZZZ']];
    }

    /**
     * @param array<string, mixed> $body
     */
    #[DataProvider('refusedBodies')]
    public function testAPartialOrWrongBodyIsA400AndRecordsNothing(array $body): void
    {
        $this->login();

        $this->send('POST', '/api/health/food-intakes', $body);

        self::assertContains($this->client->getResponse()->getStatusCode(), [400, 422]);
        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testPatchChangesTheFiguresAndPublishes(): void
    {
        $this->login();

        $this->send('PATCH', '/api/health/food-intakes/'.$this->id('apple_4'), ['kcal' => 95, 'quantityGrams' => null]);

        self::assertResponseIsSuccessful();
        $intake = $this->em()->find(FoodIntake::class, $this->id('apple_4'));
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame(95, $intake->getKcal());
        self::assertNull($intake->getQuantityGrams());
        self::assertSame('Pomme', $intake->getLabel());
        $this->assertMercureUpdatePublished('/api/health/food-intakes/'.$this->id('apple_4'));
    }

    public function testPatchRefusesAnEmptyLabel(): void
    {
        $this->login();

        $this->send('PATCH', '/api/health/food-intakes/'.$this->id('apple_4'), ['label' => '']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('Pomme', $this->em()->find(FoodIntake::class, $this->id('apple_4'))?->getLabel());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function refusedPatches(): iterable
    {
        yield 'an emptied macro' => [['proteinG' => null]];
        yield 'an emptied moment' => [['eatenAt' => null]];
        yield 'an emptied kcal' => [['kcal' => null]];
        yield 'another meal' => [['mealId' => '01JZZZZZZZZZZZZZZZZZZZZZZZ']];
        yield 'a Ciqual food set afterwards' => [['ciqualCode' => '20020']];
    }

    /**
     * Never a 500, never an intake changed: a meal given back to the planned
     * ones by a PATCH would let it be eaten twice.
     *
     * @param array<string, mixed> $body
     */
    #[DataProvider('refusedPatches')]
    public function testAPatchThatEmptiesAFigureOrMovesTheOriginIsA400(array $body): void
    {
        $this->login();

        $this->send('PATCH', '/api/health/food-intakes/'.$this->id('apple_4'), $body);

        self::assertResponseStatusCodeSame(400);
        $apple = $this->em()->find(FoodIntake::class, $this->id('apple_4'));
        self::assertInstanceOf(FoodIntake::class, $apple);
        self::assertSame(78, $apple->getKcal());
        self::assertSame(0.4, $apple->getProteinG());
        self::assertNull($apple->getMealId());
        self::assertNull($apple->getCiqualCode());
        $this->assertMercureUpdateCount(0);
    }

    public function testAMealsIntakeKeepsItsMeal(): void
    {
        $this->login();
        $this->send('POST', '/api/health/food-intakes', ['mealId' => $this->id('lunch_5')]);
        $id = $this->response()['id'];

        $this->send('PATCH', '/api/health/food-intakes/'.$id, ['mealId' => null]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame($this->id('lunch_5'), $this->em()->find(FoodIntake::class, $id)?->getMealId());
        self::assertSame(0, $this->balance()['plannedKcal']);
    }

    public function testDeleteRemovesTheIntakeAndPublishes(): void
    {
        $this->login();

        $this->send('DELETE', '/api/health/food-intakes/'.$this->id('apple_4'));

        self::assertResponseStatusCodeSame(204);
        self::assertSame(self::ROWS - 1, $this->rows());
        $this->assertMercureDeletePublished('/api/health/food-intakes/'.$this->id('apple_4'));
    }
}
