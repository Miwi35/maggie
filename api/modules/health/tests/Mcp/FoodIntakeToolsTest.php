<?php

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Enum\FoodIntakeSource;
use Maggie\Health\Mcp\Tool\GetDailyBalanceTool;
use Maggie\Health\Mcp\Tool\ManageFoodIntakesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** manage_food_intakes and get_daily_balance (MAG-748): « j'ai mangé une salade César à midi » first. */
class FoodIntakeToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    private const ROWS = 2;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-05T13:10:00+01:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/food_intake.yaml');
        // The fixtures' recipes do not know their lines until they are read back.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageFoodIntakesTool::class);
        \assert($tool instanceof ManageFoodIntakesTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function balance(string $date): array
    {
        $tool = self::getContainer()->get(GetDailyBalanceTool::class);
        \assert($tool instanceof GetDailyBalanceTool);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
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

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('list', date: '2026-11-05'));
        self::assertArrayHasKey('error', $this->balance('2026-11-05'));
    }

    public function testAnUnknownActionOrAMissingArgumentIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('eat')['error']);
        self::assertSame('date', $this->call('list')['field']);
        self::assertSame('foodIntakeId', $this->call('update', kcal: 100)['field']);
        self::assertSame('foodIntakeId', $this->call('delete')['field']);
    }

    /** « J'ai mangé une salade César à midi »: told to Maggie, on the day of its moment. */
    public function testASaladTellsTheJournalAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', eatenAt: '2026-11-05T12:00:00+01:00', label: 'Salade César', kcal: 450, proteinG: 25.0, carbsG: 20.0, fatG: 30.0);

        self::assertTrue($data['success']);
        self::assertSame('conversation', $data['intake']['source']);
        self::assertSame('Maggie', $data['intake']['sourceLabel']);
        $intake = $this->em()->getRepository(FoodIntake::class)->find($data['intake']['id']);
        self::assertInstanceOf(FoodIntake::class, $intake);
        self::assertSame('Salade César', $intake->getLabel());
        self::assertSame(450, $intake->getKcal());
        self::assertSame('2026-11-05', $intake->getDate()?->format('Y-m-d'));
        self::assertSame(FoodIntakeSource::Conversation, $intake->getSource());
        self::assertSame($this->id('test_user'), (string) $intake->getUser()->getId());
        $this->assertMercureUpdatePublished('/api/health/food-intakes/'.$intake->getId());

        self::assertSame(450, $this->balance('2026-11-05')['eatenKcal']);
    }

    /** A partial form is refused, and the answer carries the day's journal as it is. */
    public function testACreateMissingAFieldIsRefusedWithTheCurrentJournal(): void
    {
        $this->loginFixtureUser();

        $noKcal = $this->call('create', eatenAt: '2026-11-05T12:00:00+01:00', label: 'Salade César');
        self::assertSame('kcal', $noKcal['field']);
        self::assertSame([], $noKcal['current']['intakes']);
        self::assertSame([$this->id('lunch_5'), $this->id('dinner_5')], array_column($noKcal['current']['plannedMeals'], 'mealId'));

        self::assertSame('eatenAt', $this->call('create', label: 'Salade César', kcal: 450)['field']);
        self::assertSame('eatenAt', $this->call('create', eatenAt: '2026-11-05', label: 'Salade César', kcal: 450)['field']);

        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testListGivesTheDaysIntakesAndItsPlannedMealsNotEaten(): void
    {
        $this->loginFixtureUser();

        self::assertSame([$this->id('apple_4')], array_column($this->call('list', date: '2026-11-04')['intakes'], 'id'));

        $this->call('create', mealId: $this->id('lunch_5'));
        $day = $this->call('list', date: '2026-11-05');

        self::assertSame(['Bol du midi'], array_column($day['intakes'], 'label'));
        self::assertSame([$this->id('dinner_5')], array_column($day['plannedMeals'], 'mealId'));
        self::assertNull($day['plannedMeals'][0]['kcal']);
    }

    public function testEatingAPlannedMealCopiesItsFiguresOnce(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', mealId: $this->id('lunch_5'));

        self::assertTrue($data['success']);
        self::assertSame('meal', $data['intake']['source']);
        self::assertSame(515, $data['intake']['kcal']);
        self::assertEquals(new \DateTimeImmutable('2026-11-05T13:10:00+01:00'), new \DateTimeImmutable($data['intake']['eatenAt']));

        $again = $this->call('create', mealId: $this->id('lunch_5'));
        self::assertSame('mealId', $again['field']);
        self::assertSame(self::ROWS + 1, $this->rows());

        $balance = $this->balance('2026-11-05');
        self::assertSame(515, $balance['eatenKcal']);
        self::assertSame(0, $balance['plannedKcal']);
        self::assertSame(515 - 2048, $balance['balanceKcal']);
    }

    public function testAnotherUsersMealOrIntakeIsNeverReached(): void
    {
        $this->loginFixtureUser();

        self::assertSame('mealId', $this->call('create', mealId: $this->id('others_lunch_5'))['field']);
        self::assertStringContainsString('not found', $this->call('update', foodIntakeId: $this->id('others_snack_5'), kcal: 1)['error']);
        self::assertStringContainsString('not found', $this->call('delete', foodIntakeId: $this->id('others_snack_5'))['error']);
        self::assertSame(400, $this->em()->find(FoodIntake::class, $this->id('others_snack_5'))?->getKcal());
        self::assertSame(0, $this->balance('2026-11-05')['eatenKcal']);
    }

    public function testUpdateChangesOnlyWhatIsGivenAndRefusesWithTheCurrentIntake(): void
    {
        $this->loginFixtureUser();

        $nothing = $this->call('update', foodIntakeId: $this->id('apple_4'));
        self::assertSame('Pomme', $nothing['current']['label']);

        $refused = $this->call('update', foodIntakeId: $this->id('apple_4'), kcal: -1);
        self::assertSame('kcal', $refused['field']);
        self::assertSame(78, $refused['current']['kcal']);

        $data = $this->call('update', foodIntakeId: $this->id('apple_4'), kcal: 95);
        self::assertTrue($data['success']);
        $apple = $this->em()->find(FoodIntake::class, $this->id('apple_4'));
        self::assertInstanceOf(FoodIntake::class, $apple);
        self::assertSame(95, $apple->getKcal());
        self::assertSame(150.0, $apple->getQuantityGrams());
        $this->assertMercureUpdatePublished('/api/health/food-intakes/'.$this->id('apple_4'));
    }

    public function testDeleteRemovesTheIntake(): void
    {
        $this->loginFixtureUser();

        self::assertTrue($this->call('delete', foodIntakeId: $this->id('apple_4'))['success']);

        self::assertSame(self::ROWS - 1, $this->rows());
        $this->assertMercureDeletePublished('/api/health/food-intakes/'.$this->id('apple_4'));
    }

    public function testTheBalanceRefusesAMalformedDate(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->balance('demain'));
    }
}
