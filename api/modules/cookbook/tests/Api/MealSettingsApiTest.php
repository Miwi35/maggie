<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The meal settings over REST (MAG-482): how many guests a meal that names none is cooked for. */
class MealSettingsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/meal-settings/me';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('MealSettingsApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PUT', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"defaultGuests": 3}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheSettingsAreCreatedOnFirstReadWithTwoGuests(): void
    {
        self::assertNull($this->settingsOf('test_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = $this->decoded();
        $created = $this->settingsOf('test_user');
        self::assertNotNull($created);
        self::assertSame((string) $created->getId(), $data['id']);
        self::assertSame(2, $data['defaultGuests']);
        self::assertSame(['defaultGuests' => 2], $data['defaults']);
        $this->assertMercurePublishedOnce('/api/meal_settings/'.$created->getId());
        // Not searched, so never indexed.
        $this->assertNoElasticsearchIndexDispatched(MealSettings::class);
    }

    public function testPutChangesTheDefaultStoresAndPublishesIt(): void
    {
        $data = $this->put('{"defaultGuests": 3}');

        self::assertResponseIsSuccessful();
        self::assertSame(3, $data['defaultGuests']);

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(3, $stored->getDefaultGuests());
        $payloads = $this->mercurePayloadsOn('/api/meal_settings/'.$stored->getId());
        self::assertSame(3, end($payloads)['defaultGuests']);
        $this->assertNoElasticsearchIndexDispatched(MealSettings::class);

        // Another user's settings are not touched.
        self::assertSame(7, $this->settingsOf('other_user')?->getDefaultGuests());
    }

    public function testTheReadAfterAPutCarriesTheNewDefault(): void
    {
        $this->put('{"defaultGuests": 5}');

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertSame(5, $this->decoded()['defaultGuests']);
    }

    /** @return iterable<string, array{string}> */
    public static function bodiesThatAreRefused(): iterable
    {
        yield 'no default' => ['{}'];
        yield 'a null default' => ['{"defaultGuests": null}'];
        yield 'nobody' => ['{"defaultGuests": 0}'];
        yield 'negative' => ['{"defaultGuests": -3}'];
        yield 'a fraction' => ['{"defaultGuests": 2.5}'];
        yield 'a string' => ['{"defaultGuests": "3"}'];
        yield 'an unknown field' => ['{"defaultGuests": 3, "servings": 4}'];
        yield 'not JSON' => ['three'];
    }

    #[DataProvider('bodiesThatAreRefused')]
    public function testABodyWithoutAValidDefaultIsRefusedAndChangesNothing(string $body): void
    {
        $this->put('{"defaultGuests": 4}');
        $this->resetMercure();

        $this->put($body);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(4, $this->settingsOf('test_user')?->getDefaultGuests());
        $this->assertMercureUpdateCount(0);
    }

    /** @return array<string, mixed> */
    private function put(string $body): array
    {
        $this->client->request('PUT', self::ENDPOINT, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], $body);

        return $this->decoded();
    }

    /** @return array<string, mixed> */
    private function decoded(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function settingsOf(string $userRef): ?MealSettings
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $em->getRepository(MealSettings::class)->findOneBy(['user' => $user->getId()]);
    }
}
