<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Agenda;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AgendaApiTest extends WebTestCase
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

    /** @param array<string, mixed> $payload */
    private function patch(Agenda $entity, array $payload): void
    {
        $this->client->request('PATCH', '/api/agendas/'.$entity->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Agenda $entity): Agenda
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->find($entity->getId());
    }

    private function load(): Agenda
    {
        $this->loadFixtures('AgendaApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('test_agenda');
    }

    public function testPatchRequiresAuthentication(): void
    {
        $entity = $this->load();

        $this->client->request('PATCH', '/api/agendas/'.$entity->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['description' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullFieldsClearsThem(): void
    {
        $agenda = $this->load();

        $this->patch($agenda, ['description' => null, 'color' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($agenda);
        self::assertNull($reloaded->getDescription());
        self::assertNull($reloaded->getColor());
        self::assertSame('Test Agenda', $reloaded->getName());
        self::assertTrue($reloaded->isDefault());
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testPatchLeavesFieldsOutOfThePayloadUntouched(): void
    {
        $agenda = $this->load();

        $this->patch($agenda, ['name' => 'Renamed']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($agenda);
        self::assertSame('Renamed', $reloaded->getName());
        self::assertSame('Agenda notes', $reloaded->getDescription());
        self::assertSame('#e91e63', $reloaded->getColor());
    }

    public function testPatchingIsDefaultMovesTheDefaultAndSerialisesItAsDefault(): void
    {
        $this->load();
        $second = $this->getFixture('second_agenda');
        // A request starts with an empty identity map: the promoted agenda is loaded before the demoted one.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $this->patch($second, ['isDefault' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($this->reload($second)->isDefault());
        self::assertFalse($this->reload($this->getFixture('test_agenda'))->isDefault());
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);

        $this->client->request('GET', '/api/agendas/'.$second->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        $body = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($body['default']);
    }
}
