<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * One-off envelopes (Hellfest 2026) over REST, and the hand attribution of a
 * spend to one from the transaction (MAG-414).
 */
class OneOffEnvelopeApiTest extends WebTestCase
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
        $this->loadFixtures('one_off_envelope.yaml');
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

    private function reloadTransaction(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($fixture)->getId());
    }

    public function testCreateOneOffEnvelopeRequiresAuthentication(): void
    {
        $this->send('POST', '/api/envelopes', ['mode' => 'one_off', 'name' => 'Vacances', 'amountCents' => 150000], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateOneOffEnvelopePersistsWithoutCategoryNorPeriod(): void
    {
        $this->login();

        $this->send('POST', '/api/envelopes', ['mode' => 'one_off', 'name' => 'Vacances', 'amountCents' => 150000]);

        self::assertResponseStatusCodeSame(201);
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('one_off', $data['mode']);
        self::assertSame('Vacances', $data['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->getRepository(Envelope::class)->findOneBy(['name' => 'Vacances']);
        self::assertNotNull($created);
        self::assertSame(BudgetMode::OneOff, $created->getMode());
        self::assertSame(150000, $created->getAmountCents());
        self::assertNull($created->getCategory());
        self::assertNull($created->getYear());
        self::assertNull($created->getMonth());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testCreateOneOffEnvelopeWithoutNameIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/envelopes', ['mode' => 'one_off', 'amountCents' => 150000]);

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('requires a name', (string) $this->client->getResponse()->getContent());
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateOneOffEnvelopeWithACategoryIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/envelopes', [
            'mode' => 'one_off',
            'name' => 'Vacances',
            'amountCents' => 150000,
            'category' => $this->iri('categories', 'leisure'),
        ]);

        self::assertResponseStatusCodeSame(422);
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateMonthlyEnvelopeWithoutCategoryIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/envelopes', ['mode' => 'monthly', 'amountCents' => 15000, 'year' => 2026, 'month' => 11]);

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('requires a category', (string) $this->client->getResponse()->getContent());
        $this->assertMercureUpdateCount(0);
    }

    public function testRenameAOneOffEnvelope(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('envelopes', 'hellfest'), ['name' => 'Hellfest 2026 — Clisson', 'amountCents' => 45000]);

        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $envelope = $em->find(Envelope::class, $this->getFixture('hellfest')->getId());
        self::assertSame('Hellfest 2026 — Clisson', $envelope->getName());
        self::assertSame(45000, $envelope->getAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testAMonthlyEnvelopeCannotBecomeAOneOff(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('envelopes', 'leisure_october'), ['mode' => 'one_off', 'name' => 'Loisirs', 'category' => null, 'year' => null, 'month' => null]);

        self::assertResponseStatusCodeSame(422);
        $this->assertMercureUpdateCount(0);
    }

    public function testAttributeASpendRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('transactions', 'ticket'), ['oneOffEnvelope' => $this->iri('envelopes', 'hellfest')], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAttributeASpendToAOneOffEnvelopeKeepsItsCategory(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'ticket'), ['oneOffEnvelope' => $this->iri('envelopes', 'hellfest')]);

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame($this->iri('envelopes', 'hellfest'), $data['oneOffEnvelope']);

        $ticket = $this->reloadTransaction('ticket');
        self::assertSame((string) $this->getFixture('hellfest')->getId(), (string) $ticket->getOneOffEnvelope()?->getId());
        self::assertSame('Loisirs', $ticket->getCategory()?->getName());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testRemoveTheAttributionWithNull(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'tent'), ['oneOffEnvelope' => null]);

        self::assertResponseIsSuccessful();
        $tent = $this->reloadTransaction('tent');
        self::assertNull($tent->getOneOffEnvelope());
        self::assertSame('Loisirs', $tent->getCategory()?->getName());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAPatchThatOmitsTheAttributionLeavesIt(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'tent'), ['label' => 'Tente 2 places']);

        self::assertResponseIsSuccessful();
        self::assertSame((string) $this->getFixture('hellfest')->getId(), (string) $this->reloadTransaction('tent')->getOneOffEnvelope()?->getId());
    }

    public function testAttributeASpendToAMonthlyEnvelopeIsRefused(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'ticket'), ['oneOffEnvelope' => $this->iri('envelopes', 'leisure_october')]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
        $this->assertMercureUpdateCount(0);
    }

    public function testAttributeASpendToAnotherUsersEnvelopeIsRefused(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'ticket'), ['oneOffEnvelope' => $this->iri('envelopes', 'other_hellfest')]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringNotContainsString('Fête secrète', (string) $this->client->getResponse()->getContent());
        self::assertNull($this->reloadTransaction('ticket')->getOneOffEnvelope());
        $this->assertMercureUpdateCount(0);
    }

    public function testDeletingAOneOffEnvelopeFreesItsSpends(): void
    {
        $this->login();

        $this->client->request('DELETE', $this->iri('envelopes', 'hellfest'), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);
        $tent = $this->reloadTransaction('tent');
        self::assertNull($tent->getOneOffEnvelope());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchDeleteDispatched('envelopes');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testTheListFiltersTheOneOffEnvelopes(): void
    {
        $this->login();

        $this->client->request('GET', '/api/envelopes?mode=one_off', [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(['Hellfest 2026'], array_column($data['member'], 'name'));
    }

    public function testAOneOffEnvelopeGivenAYearIsRejected(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('envelopes', 'hellfest'), ['year' => 2026]);

        self::assertResponseStatusCodeSame(422);
        $this->assertMercureUpdateCount(0);
    }

    public function testAttributingAnIncomeIsRefused(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $refund = $em->find(Transaction::class, $this->getFixture('bar')->getId());
        $refund->setAmountCents(2500);
        $em->flush();
        $this->resetMercure();

        $this->send('PATCH', $this->iri('transactions', 'bar'), ['oneOffEnvelope' => $this->iri('envelopes', 'hellfest')]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->reloadTransaction('bar')->getOneOffEnvelope());
        $this->assertMercureUpdateCount(0);
    }
}
