<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * « Pour les recettes » (MAG-590): the ingredients the meals of a period need.
 *
 * The world is the one of {@see \Maggie\MealGrocery\Tests\Service\ShortageAlertsTest}:
 * meals on 14 and 15 October (and one on the 16th), parmesan and butter out, cream low, flour and eggs in stock.
 */
final class RecipeNeedsApiTest extends WebTestCase
{
    use AuthenticatedTestTrait;
    use FixtureLoaderTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(\dirname(__DIR__).'/Service/fixtures/ShortageAlertsTest.yaml');
        $this->em()->clear();
    }

    public function testAnonymousIsRefused(): void
    {
        $this->get('from=2026-10-14&to=2026-10-15&missing=true', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function badQueries(): iterable
    {
        yield 'no period' => [''];
        yield 'no end' => ['from=2026-10-14'];
        yield 'not a date' => ['from=demain&to=2026-10-15'];
        yield 'impossible date' => ['from=2026-02-30&to=2026-03-02'];
        yield 'end before start' => ['from=2026-10-15&to=2026-10-14'];
        yield 'period too long' => ['from=2026-10-01&to=2026-12-01'];
        yield 'missing is not a boolean' => ['from=2026-10-14&to=2026-10-15&missing=maybe'];
    }

    #[DataProvider('badQueries')]
    public function testABadQueryIsRefused(string $query): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->get($query);

        self::assertResponseStatusCodeSame(400);
    }

    public function testMissingKeepsWhatTheOwnerIsLowOnOrOutOfInTheOrderOfTheMeals(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('from=2026-10-14&to=2026-10-15&missing=true');

        self::assertResponseIsSuccessful();
        self::assertSame(
            [
                'Beurre · lunch · 2026-10-14',
                'Parmesan · dinner · 2026-10-14',
                'Crème fraîche · lunch · 2026-10-15',
                'Lait · lunch · 2026-10-15',
                'Parmesan · dinner · 2026-10-15',
            ],
            array_map(static fn (array $m): string => $m['name'].' · '.$m['slot'].' · '.$m['date'], $body['member']),
        );
        self::assertSame(5, $body['totalItems']);
        self::assertSame([true], array_values(array_unique(array_column($body['member'], 'missing'))));

        $first = $body['member'][1];
        self::assertSame((string) $this->getFixture('parmesan')->getId(), $first['productId']);
        self::assertSame((string) $this->getFixture('dinner_today')->getId(), $first['mealId']);
        self::assertSame('Gratin', $first['mealName']);
    }

    public function testWithoutMissingEveryIngredientComesWithItsFlag(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('from=2026-10-14&to=2026-10-14');

        self::assertResponseIsSuccessful();
        $flags = [];
        foreach ($body['member'] as $entry) {
            $flags[$entry['name']] = $entry['missing'];
        }
        ksort($flags);
        self::assertSame(['Beurre' => true, 'Farine' => false, 'Oeufs' => false, 'Parmesan' => true], $flags);
    }

    public function testAMealOutsideThePeriodIsLeftOut(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('from=2026-10-16&to=2026-10-16&missing=true');

        self::assertSame(['Pâtes'], array_column($body['member'], 'name'));
    }

    public function testAnotherUsersMealsAreNotRead(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $body = $this->get('from=2026-10-14&to=2026-10-16&missing=true');

        self::assertResponseIsSuccessful();
        self::assertSame([], $body['member']);
        self::assertSame(0, $body['totalItems']);
    }

    /**
     * @return array<string, mixed>
     */
    private function get(string $query, bool $authenticated = true): array
    {
        $this->client->request('GET', '/api/grocery/recipe-needs?'.$query, [], [], ($authenticated ? $this->authHeaders() : []) + ['HTTP_ACCEPT' => 'application/json']);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR) ?? [];
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
