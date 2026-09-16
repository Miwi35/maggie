<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EnvelopeApiTest extends WebTestCase
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

    public function testCreateEnvelopeRequiresAuthentication(): void
    {
        $this->loadFixtures('envelope.yaml');
        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/envelopes', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'category' => '/api/categories/' . $category->getId(),
            'amountCents' => 15000,
            'year' => 2026,
            'month' => 8,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateEnvelopePersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/envelopes', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'category' => '/api/categories/' . $category->getId(),
            'mode' => 'monthly',
            'amountCents' => 15000,
            'currency' => 'EUR',
            'year' => 2026,
            'month' => 8,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(15000, $data['amountCents']);
        self::assertSame('monthly', $data['mode']);
        self::assertSame(8, $data['month']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(Envelope::class)->findAll());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testCreateMonthlyEnvelopeWithoutMonthIsRejected(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/envelopes', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // 'month' missing while the default mode is monthly
            'category' => '/api/categories/' . $category->getId(),
            'amountCents' => 15000,
            'year' => 2026,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testCreateAnnualEnvelopeWithMonthIsRejected(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/envelopes', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'category' => '/api/categories/' . $category->getId(),
            'mode' => 'annual',
            'amountCents' => 120000,
            'year' => 2026,
            'month' => 8,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testUpdateEnvelopeAmountPublishesAndIndexes(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $envelope = $this->getFixture('food_july');
        $envelopeId = $envelope->getId();

        $this->client->request('PATCH', '/api/envelopes/' . $envelopeId, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'amountCents' => 45000,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(200);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(45000, $em->find(Envelope::class, $envelopeId)->getAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testDeleteEnvelopeRemovesAndPublishes(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $envelope = $this->getFixture('food_july');
        $envelopeId = $envelope->getId();

        $this->client->request('DELETE', '/api/envelopes/' . $envelopeId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Envelope::class, $envelopeId));

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchDeleteDispatched('envelopes');
    }
}
