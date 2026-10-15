<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Seasonal amounts of a monthly envelope over REST (MAG-415): Cinéma 20 € a
 * month, 45 € from November to February.
 */
class SeasonalEnvelopeApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const array WINTER = ['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('seasonal_envelope.yaml');
    }

    /** @param array<string, mixed> $body */
    private function send(string $method, string $uri, array $body, bool $authenticated = true): void
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function login(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
    }

    private function reload(string $id): Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Envelope::class, $id);
    }

    /** @param array<string, mixed> $overrides */
    private function createCinema(array $overrides = []): void
    {
        $this->send('POST', '/api/envelopes', $overrides + [
            'category' => $this->iri('categories', 'cinema'),
            'mode' => 'monthly',
            'amountCents' => 2000,
            'year' => 2026,
            'month' => 12,
            'seasonalAmounts' => [self::WINTER],
        ]);
    }

    public function testCreateSeasonalEnvelopeRequiresAuthentication(): void
    {
        $this->send('POST', '/api/envelopes', [
            'category' => $this->iri('categories', 'cinema'),
            'amountCents' => 2000,
            'year' => 2026,
            'month' => 12,
            'seasonalAmounts' => [self::WINTER],
        ], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateMonthlyEnvelopeKeepsItsSeasons(): void
    {
        $this->login();

        $this->createCinema();

        self::assertResponseStatusCodeSame(201);
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame([self::WINTER], $data['seasonalAmounts']);

        $created = $this->reload(basename($data['@id']));
        self::assertSame(2000, $created->getAmountCents());
        self::assertSame([self::WINTER], $created->getSeasonalAmounts());
        // December is in the season: it is budgeted 45 €, not 20 €.
        self::assertSame(4500, $created->getMonthAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testOverlappingSeasonsAreRefusedWith400(): void
    {
        $this->login();

        $this->createCinema(['seasonalAmounts' => [
            self::WINTER,
            ['fromMonth' => 2, 'toMonth' => 3, 'amountCents' => 3000],
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('overlap', (string) $this->client->getResponse()->getContent());
        self::assertCount(3, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Envelope::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }

    public function testAMonthOutOfTheYearIsRefusedWith400(): void
    {
        $this->login();

        $this->createCinema(['seasonalAmounts' => [['fromMonth' => 11, 'toMonth' => 13, 'amountCents' => 4500]]]);

        self::assertResponseStatusCodeSame(400);
        $this->assertMercureUpdateCount(0);
    }

    public function testAnAnnualEnvelopeHasNoSeasons(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('envelopes', 'travel_2026'), ['seasonalAmounts' => [self::WINTER]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->reload((string) $this->getFixture('travel_2026')->getId())->getSeasonalAmounts());
        $this->assertMercureUpdateCount(0);
    }

    public function testPatchReplacesTheSeasons(): void
    {
        $this->login();
        $id = (string) $this->getFixture('cinema_october')->getId();

        $summer = ['fromMonth' => 7, 'toMonth' => 8, 'amountCents' => 1000];
        $this->send('PATCH', $this->iri('envelopes', 'cinema_october'), ['seasonalAmounts' => [self::WINTER, $summer]]);

        self::assertResponseIsSuccessful();
        self::assertSame([self::WINTER, $summer], $this->reload($id)->getSeasonalAmounts());
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);

        $this->send('PATCH', $this->iri('envelopes', 'cinema_october'), ['seasonalAmounts' => []]);

        self::assertResponseIsSuccessful();
        self::assertSame([], $this->reload($id)->getSeasonalAmounts());
    }
}
