<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\TreasurySettings;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The treasury settings over REST (MAG-276): created on first read, written by a dedicated PATCH. */
class TreasurySettingsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/finance/treasury-settings';
    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/savings_advances.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    private function signIn(string $as = 'test_user'): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->authenticateAsUser($this->getFixture($as));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function patch(string $body): array
    {
        $this->client->request('PATCH', self::ENDPOINT, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], $body);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function settingsOf(string $userRef): ?TreasurySettings
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $em->getRepository(TreasurySettings::class)->findOneBy(['user' => $user->getId()]);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PATCH', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"salaryExpectedDay": 25}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheSettingsAreCreatedOnFirstRead(): void
    {
        $this->signIn('bare_user');
        self::assertNull($this->settingsOf('bare_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $created = $this->settingsOf('bare_user');
        self::assertNotNull($created);
        self::assertSame((string) $created->getId(), $data['id']);
        self::assertNull($data['salaryExpectedDay']);
        self::assertFalse($data['salaryDelay']['isConfigured']);
        $this->assertMercurePublishedOnce('/api/treasury_settings/'.$created->getId());
    }

    public function testPatchWritesTheDayAndPublishesIt(): void
    {
        $this->signIn();

        $data = $this->patch('{"salaryExpectedDay": 27}');

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertSame(27, $data['salaryExpectedDay']);
        self::assertSame(27, $data['salaryDelay']['salaryExpectedDay']);
        $stored = $this->settingsOf('test_user');
        self::assertSame(27, $stored?->getSalaryExpectedDay());
        self::assertSame(27, $this->mercurePayloadsOn('/api/treasury_settings/'.$stored->getId())[0]['salaryExpectedDay']);
        // The settings are not searched: nothing is indexed.
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);
        self::assertSame(25, $this->settingsOf('other_user')?->getSalaryExpectedDay());
    }

    public function testPatchWritesTheOverdraftLimitAloneAndKeepsTheDay(): void
    {
        $this->signIn();

        $data = $this->patch('{"overdraftLimitCents": 30000}');

        self::assertResponseIsSuccessful();
        self::assertSame(30000, $data['overdraftLimitCents']);
        self::assertSame(25, $data['salaryExpectedDay']);
        $stored = $this->settingsOf('test_user');
        self::assertSame(30000, $stored?->getOverdraftLimitCents());
        self::assertSame(25, $stored->getSalaryExpectedDay());
        self::assertSame(['@id' => '/api/treasury_settings/'.$stored->getId(), 'overdraftLimitCents' => 30000], $this->mercurePayloadsOn('/api/treasury_settings/'.$stored->getId())[0]);
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);
        self::assertSame(0, $this->settingsOf('other_user')?->getOverdraftLimitCents());
    }

    /** @return iterable<string, array{string}> */
    public static function badLimits(): iterable
    {
        yield 'negative' => ['{"overdraftLimitCents": -1}'];
        yield 'not an integer' => ['{"overdraftLimitCents": 300.5}'];
        yield 'a string' => ['{"overdraftLimitCents": "30000"}'];
        yield 'null' => ['{"overdraftLimitCents": null}'];
    }

    #[DataProvider('badLimits')]
    public function testABadOverdraftLimitIsA400AndChangesNothing(string $body): void
    {
        $this->signIn();

        $data = $this->patch($body);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('overdraftLimitCents', $data['error']);
        self::assertSame(0, $this->settingsOf('test_user')?->getOverdraftLimitCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testNullForgetsTheDay(): void
    {
        $this->signIn();

        $data = $this->patch('{"salaryExpectedDay": null}');

        self::assertResponseIsSuccessful();
        self::assertNull($data['salaryExpectedDay']);
        self::assertNull($this->settingsOf('test_user')?->getSalaryExpectedDay());
    }

    /** @return iterable<string, array{string}> */
    public static function badBodies(): iterable
    {
        yield 'below the month' => ['{"salaryExpectedDay": 0}'];
        yield 'past the month' => ['{"salaryExpectedDay": 32}'];
        yield 'not an integer' => ['{"salaryExpectedDay": 25.5}'];
        yield 'a string' => ['{"salaryExpectedDay": "25"}'];
        yield 'missing' => ['{}'];
        yield 'not JSON' => ['salaryExpectedDay=25'];
    }

    #[DataProvider('badBodies')]
    public function testABadDayIsA400AndChangesNothing(string $body): void
    {
        $this->signIn();

        $data = $this->patch($body);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $data);
        self::assertSame(25, $this->settingsOf('test_user')?->getSalaryExpectedDay());
        $this->assertMercureUpdateCount(0);
    }
}
