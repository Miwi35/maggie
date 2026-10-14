<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Uid\Ulid;

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
            'category' => '/api/categories/'.$category->getId(),
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
            'category' => '/api/categories/'.$category->getId(),
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
            'category' => '/api/categories/'.$category->getId(),
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
            'category' => '/api/categories/'.$category->getId(),
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

        $this->client->request('PATCH', '/api/envelopes/'.$envelopeId, [], [], array_merge([
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

    public function testUpdateAlertThresholdRequiresAuthentication(): void
    {
        $this->loadFixtures('envelope.yaml');
        $envelopeId = $this->getFixture('food_july')->getId();

        $this->client->request('PATCH', '/api/envelopes/'.$envelopeId, [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode(['alertThresholdPercent' => 90], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testUpdateAlertThresholdPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $envelopeId = $this->getFixture('food_july')->getId();

        $this->patch($envelopeId, ['alertThresholdPercent' => 90]);

        self::assertResponseStatusCodeSame(200);
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(90, $data['alertThresholdPercent']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(90, $em->find(Envelope::class, $envelopeId)->getAlertThresholdPercent());

        $payloads = $this->mercurePayloadsOn('/envelopes/'.$envelopeId);
        self::assertSame(90, end($payloads)['alertThresholdPercent']);
        $this->assertElasticsearchIndexDispatched(Envelope::class);

        // Null is « back to the default », not « unchanged ».
        $this->patch($envelopeId, ['alertThresholdPercent' => null]);
        self::assertResponseStatusCodeSame(200);
        $em->clear();
        $envelope = $em->find(Envelope::class, $envelopeId);
        self::assertNull($envelope->getAlertThresholdPercent());
        self::assertSame(80, $envelope->getEffectiveAlertThresholdPercent());
    }

    /** @return iterable<string, array{int}> */
    public static function thresholdsOutOfBounds(): iterable
    {
        yield 'zero' => [0];
        // 100 % always alerts: a threshold there would say nothing more.
        yield 'the limit itself' => [100];
        yield 'past the limit' => [120];
    }

    #[DataProvider('thresholdsOutOfBounds')]
    public function testAThresholdOutOfBoundsIsRejected(int $threshold): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $envelopeId = $this->getFixture('food_july')->getId();

        $this->patch($envelopeId, ['alertThresholdPercent' => $threshold]);

        self::assertResponseStatusCodeSame(422);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Envelope::class, $envelopeId)->getAlertThresholdPercent());
    }

    public function testCreateEnvelopeKeepsItsAlertThreshold(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/envelopes', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'category' => '/api/categories/'.$this->getFixture('leisure')->getId(),
            'amountCents' => 30000,
            'year' => 2026,
            'month' => 8,
            'alertThresholdPercent' => 70,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $id = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['id'];
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(70, $em->find(Envelope::class, $id)->getAlertThresholdPercent());
    }

    /** @param array<string, mixed> $body */
    private function patch(Ulid $envelopeId, array $body): void
    {
        $this->client->request('PATCH', '/api/envelopes/'.$envelopeId, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testDeleteEnvelopeRemovesAndPublishes(): void
    {
        $this->loadFixtures('envelope.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $envelope = $this->getFixture('food_july');
        $envelopeId = $envelope->getId();

        $this->client->request('DELETE', '/api/envelopes/'.$envelopeId, [], [], array_merge([
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
