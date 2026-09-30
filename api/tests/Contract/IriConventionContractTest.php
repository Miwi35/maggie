<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A relation the API hands out is a full IRI, and it is handed back unchanged.
 *
 * c359b43: the admin read `calendarId` from a Hydra response — already
 * "/api/agendas/01H…" — and wrapped it in "/api/calendars/" before sending it
 * back. The path doubled, event creation broke, and the only symptom was a
 * form that would not submit.
 *
 * The rule is one sentence: what the provider returns is the identifier, not
 * something to build an identifier from. These tests pin both ends of it —
 * the API emits full IRIs, and it accepts its own IRIs verbatim. A client
 * that prefixes one is then wrong against a documented, tested contract
 * rather than against a convention nobody wrote down.
 */
final class IriConventionContractTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    /**
     * Relations come out as "/api/<resource>/<id>", never as a bare id.
     *
     * Asserted on a real Hydra response rather than on the serializer's
     * configuration, because the client only ever sees the response.
     */
    public function testARelationIsServedAsAFullIri(): void
    {
        $this->loadContractFixtures();

        $event = $this->get('/api/events/' . $this->fixtureEvent()->getId());

        self::assertIsString($event['agenda'], 'The agenda relation is not a string; the clients read it as an IRI.');
        self::assertStringStartsWith('/api/agendas/', $event['agenda'], sprintf(
            'The agenda relation is "%s". A client that treats it as an id and prefixes it produces "/api/agendas//api/agendas/…" — c359b43.',
            $event['agenda'],
        ));
        self::assertSame('/api/agendas/' . $this->fixtureAgenda()->getId(), $event['agenda']);

        // The resource's own identifier follows the same rule.
        self::assertStringStartsWith('/api/events/', $event['@id']);
    }

    public function testACollectionServesTheSameIrisAsTheItem(): void
    {
        $this->loadContractFixtures();

        $collection = $this->get('/api/events');

        self::assertArrayHasKey('member', $collection, 'API Platform 4 collections use the "member" key.');
        self::assertNotEmpty($collection['member']);

        foreach ($collection['member'] as $member) {
            self::assertStringStartsWith('/api/agendas/', $member['agenda'], 'A collection member carries a relation that is not a full IRI.');
        }
    }

    /**
     * The round trip c359b43 broke: read an IRI, send it back untouched,
     * get a resource that points at the same thing.
     */
    public function testTheApiAcceptsItsOwnIriUnchangedOnCreate(): void
    {
        $this->loadContractFixtures();

        $agendaIri = $this->get('/api/events/' . $this->fixtureEvent()->getId())['agenda'];

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'summary' => 'Created from the IRI the API just handed out',
            'startAt' => '2026-04-01T09:00:00+00:00',
            'endAt' => '2026-04-01T10:00:00+00:00',
            'agenda' => $agendaIri,
        ], \JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201, sprintf(
            "Posting back the agenda IRI the API had just returned (\"%s\") was refused.\nThe clients have nothing else to send: the IRI is what the collection gives them.\nResponse: %s",
            $agendaIri,
            (string) $this->client->getResponse()->getContent(),
        ));

        $created = $this->decodeResponse();
        self::assertSame($agendaIri, $created['agenda'], 'The created event does not point at the agenda that was asked for.');
    }

    public function testTheApiAcceptsItsOwnIriUnchangedOnPatch(): void
    {
        $this->loadContractFixtures();

        $event = $this->fixtureEvent();
        $agendaIri = $this->get('/api/events/' . $event->getId())['agenda'];

        $this->client->request('PATCH', '/api/events/' . $event->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['agenda' => $agendaIri], \JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful(sprintf(
            'Patching an event with the agenda IRI the API itself returned was refused. Response: %s',
            (string) $this->client->getResponse()->getContent(),
        ));
        self::assertSame($agendaIri, $this->decodeResponse()['agenda']);
    }

    /**
     * The doubled-prefix shape, sent on purpose. It must be refused — a 201
     * here would mean the API silently accepts both spellings, and the two
     * clients would drift apart without anybody noticing which one is right.
     */
    public function testAnIriThatHasBeenPrefixedAgainIsRefused(): void
    {
        $this->loadContractFixtures();

        $agendaIri = $this->get('/api/events/' . $this->fixtureEvent()->getId())['agenda'];

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'summary' => 'Created with a doubled prefix',
            'startAt' => '2026-04-01T09:00:00+00:00',
            'endAt' => '2026-04-01T10:00:00+00:00',
            'agenda' => '/api/agendas' . $agendaIri,
        ], \JSON_THROW_ON_ERROR));

        self::assertGreaterThanOrEqual(400, $this->client->getResponse()->getStatusCode(), sprintf(
            'The API accepted "%s", an IRI that had been prefixed a second time. Accepting both spellings is how the clients end up disagreeing about which one is the contract.',
            '/api/agendas' . $agendaIri,
        ));
    }

    private function loadContractFixtures(): void
    {
        $this->loadFixtures('IriConvention.yaml');

        $user = $this->getFixture('contract_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
    }

    private function fixtureEvent(): Event
    {
        $event = $this->getFixture('contract_event');
        self::assertInstanceOf(Event::class, $event);

        return $event;
    }

    private function fixtureAgenda(): Agenda
    {
        $agenda = $this->getFixture('contract_agenda');
        self::assertInstanceOf(Agenda::class, $agenda);

        return $agenda;
    }

    /** @return array<string, mixed> */
    private function get(string $path): array
    {
        $this->client->request('GET', $path, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful(sprintf('GET %s failed.', $path));

        return $this->decodeResponse();
    }

    /** @return array<string, mixed> */
    private function decodeResponse(): array
    {
        $decoded = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($decoded);

        /** @var array<string, mixed> $decoded */
        return $decoded;
    }
}
