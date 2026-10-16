<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EnvelopeAdjustmentsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->loadFixtures('envelope_adjustments.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed> $body */
    private function post(array $body): void
    {
        $this->client->request('POST', '/api/finance/envelope-adjustments', [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function octoberEnvelope(string $category): ?Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture($category)->getId(),
            'mode' => BudgetMode::Monthly,
            'year' => 2026,
            'month' => 10,
        ]);
    }

    public function testUnauthenticatedReadReturns401(): void
    {
        $this->client->request('GET', '/api/finance/envelope-adjustments?month=2026-09');

        self::assertResponseStatusCodeSame(401);
    }

    public function testUnauthenticatedApplyReturns401(): void
    {
        $this->client->request('POST', '/api/finance/envelope-adjustments', [], [], ['CONTENT_TYPE' => 'application/json'], '{}');

        self::assertResponseStatusCodeSame(401);
    }

    public function testABadMonthReturns400(): void
    {
        $this->login();

        $this->client->request('GET', '/api/finance/envelope-adjustments?month=2026-13', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function badBodies(): iterable
    {
        yield 'no month' => [['adjustments' => [['categoryId' => '01JA0000000000000000000000', 'amountCents' => 100]]]];
        yield 'no adjustments' => [['month' => '2026-09']];
        yield 'empty adjustments' => [['month' => '2026-09', 'adjustments' => []]];
        yield 'bad category' => [['month' => '2026-09', 'adjustments' => [['categoryId' => 'nope', 'amountCents' => 100]]]];
        yield 'float amount' => [['month' => '2026-09', 'adjustments' => [['categoryId' => '01JA0000000000000000000000', 'amountCents' => 10.5]]]];
        yield 'negative amount' => [['month' => '2026-09', 'adjustments' => [['categoryId' => '01JA0000000000000000000000', 'amountCents' => -1]]]];
    }

    /** @param array<string, mixed> $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('badBodies')]
    public function testABadBodyReturns400(array $body): void
    {
        $this->login();

        $this->post($body);

        self::assertResponseStatusCodeSame(400);
    }

    public function testACategoryOfSomeoneElseReturns400AndWritesNothing(): void
    {
        $this->login();

        $this->post(['month' => '2026-09', 'adjustments' => [
            ['categoryId' => (string) $this->getFixture('leisure')->getId(), 'amountCents' => 25000],
            ['categoryId' => (string) $this->getFixture('foreign')->getId(), 'amountCents' => 100],
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(20000, $this->octoberEnvelope('leisure')->getAmountCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testACategoryWithoutEnvelopeInTheMonthReturns400(): void
    {
        $this->login();

        $this->post(['month' => '2026-05', 'adjustments' => [['categoryId' => (string) $this->getFixture('cinema')->getId(), 'amountCents' => 2000]]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('No envelope', $this->json()['error']);
    }

    public function testReadingListsTheEnvelopesAndOnlySuggestsForTheOnesThatDoNotFit(): void
    {
        $this->login();

        $this->client->request('GET', '/api/finance/envelope-adjustments?month=2026-09', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = $this->json();
        self::assertSame('2026-10', $data['targetMonth']);
        self::assertCount(5, $data['envelopes']);
        $suggested = array_column(array_filter($data['envelopes'], static fn (array $l): bool => null !== $l['suggestion']), 'categoryName');
        sort($suggested);
        self::assertSame(['Cadeaux', 'Cinéma', 'Loisirs'], $suggested);
        self::assertSame(3, $data['suggestionCount']);
    }

    public function testDoingNothingChangesNothing(): void
    {
        $this->login();

        $this->client->request('GET', '/api/finance/envelope-adjustments?month=2026-09', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame(20000, $this->octoberEnvelope('leisure')->getAmountCents());
        self::assertNull($this->octoberEnvelope('cinema'));
        $this->assertMercureUpdateCount(0);
    }

    public function testApplyingASuggestionSetsNextMonthsAmount(): void
    {
        $this->login();

        $this->post(['month' => '2026-09', 'adjustments' => [['categoryId' => (string) $this->getFixture('leisure')->getId(), 'amountCents' => 25000]]]);

        self::assertResponseIsSuccessful();
        $data = $this->json();
        self::assertTrue($data['success']);
        self::assertSame(25000, $data['applied'][0]['amountCents']);
        self::assertFalse($data['applied'][0]['created']);
        self::assertSame(25000, $this->octoberEnvelope('leisure')->getAmountCents());
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testApplyingCreatesNextMonthsEnvelopeWhenThereIsNone(): void
    {
        $this->login();

        $this->post(['month' => '2026-09', 'adjustments' => [['categoryId' => (string) $this->getFixture('cinema')->getId(), 'amountCents' => 2000]]]);

        self::assertResponseIsSuccessful();
        self::assertTrue($this->json()['applied'][0]['created']);
        $created = $this->octoberEnvelope('cinema');
        self::assertSame(2000, $created->getAmountCents());
        self::assertSame('EUR', $created->getCurrency());
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatchedFor(Envelope::class, $this->json()['applied'][0]['envelopeId']);
    }

    public function testASeasonalAdjustmentCutsTheSeasonAroundTheMonth(): void
    {
        $this->login();

        $this->post(['month' => '2026-09', 'adjustments' => [['categoryId' => (string) $this->getFixture('gifts')->getId(), 'amountCents' => 10000, 'seasonal' => true]]]);

        self::assertResponseIsSuccessful();
        $created = $this->octoberEnvelope('gifts');
        self::assertSame(10000, $created->getMonthAmountCents());
        self::assertEqualsCanonicalizing([
            ['fromMonth' => 9, 'toMonth' => 9, 'amountCents' => 7000],
            ['fromMonth' => 11, 'toMonth' => 11, 'amountCents' => 7000],
            ['fromMonth' => 10, 'toMonth' => 10, 'amountCents' => 10000],
        ], $created->getSeasonalAmounts());
    }
}
