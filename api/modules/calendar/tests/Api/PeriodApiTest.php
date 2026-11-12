<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The periods through the API Platform resource (MAG-809). */
class PeriodApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    /** Six weeks from Sunday 2037-03-01, Paris. */
    private const SIX_WEEKS = [
        'endAt[after]' => '2037-03-01T00:00:00+01:00',
        'startAt[before]' => '2037-04-12T00:00:00+02:00',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('PeriodApiTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed>|null $body */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): ?Period
    {
        return $this->em()->find(Period::class, $this->id($fixture));
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Period::class)->count([]);
    }

    /**
     * @param array<string, mixed> $overrides
     *
     * @return array<string, mixed>
     */
    private function timed(array $overrides = []): array
    {
        return $overrides + [
            'name' => 'Déplacement à Lyon',
            'agenda' => '/api/agendas/'.$this->id('perso'),
            'startAt' => '2037-05-04T08:00:00+02:00',
            'endAt' => '2037-05-06T20:00:00+02:00',
        ];
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->send('GET', '/api/periods', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->send('POST', '/api/periods', $this->timed(), authenticated: false);
        self::assertResponseStatusCodeSame(401);
        self::assertSame(3, $this->rows());
    }

    public function testCreateStoresTheTimedPeriod(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed(['rrule' => 'FREQ=MONTHLY']));

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('Déplacement à Lyon', $data['name']);
        self::assertFalse($data['allDay']);
        self::assertSame('/api/agendas/'.$this->id('perso'), $data['agenda']);

        $period = $this->em()->getRepository(Period::class)->findOneBy(['name' => 'Déplacement à Lyon']);
        self::assertInstanceOf(Period::class, $period);
        self::assertEquals(new \DateTimeImmutable('2037-05-04T08:00:00+02:00'), $period->getStartAt());
        self::assertEquals(new \DateTimeImmutable('2037-05-06T20:00:00+02:00'), $period->getEndAt());
        self::assertNull($period->getStartDate());
        self::assertSame('FREQ=MONTHLY', $period->getRrule());
        self::assertSame($this->id('perso'), (string) $period->getAgenda()->getId());
    }

    public function testCreateStoresTheAllDayPeriodWithExcludedEnd(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', [
            'name' => 'Vacances d\'été',
            'agenda' => '/api/agendas/'.$this->id('perso'),
            'allDay' => true,
            'startDate' => '2037-07-20',
            'endDate' => '2037-08-01',
        ]);

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('2037-07-20', $data['startDate']);
        self::assertSame('2037-08-01', $data['endDate']);
        self::assertArrayNotHasKey('startAt', $data);

        $period = $this->em()->getRepository(Period::class)->findOneBy(['name' => 'Vacances d\'été']);
        self::assertInstanceOf(Period::class, $period);
        self::assertTrue($period->isAllDay());
        self::assertSame('2037-07-20', $period->getStartDate()?->format('Y-m-d'));
        self::assertSame('2037-08-01', $period->getEndDate()?->format('Y-m-d'));
        self::assertNull($period->getStartAt());
    }

    public function testAMixOfInstantsAndDaysIs422(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed(['startDate' => '2037-05-04']));
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/periods', $this->timed(['allDay' => true, 'startDate' => '2037-05-04', 'endDate' => '2037-05-05']));
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/periods', $this->timed(['name' => '']));
        self::assertResponseStatusCodeSame(422);

        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnEndBeforeTheStartIs422(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed(['endAt' => '2037-05-04T07:00:00+02:00']));
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/periods', [
            'name' => 'À l\'envers',
            'agenda' => '/api/agendas/'.$this->id('perso'),
            'allDay' => true,
            'startDate' => '2037-07-20',
            'endDate' => '2037-07-20',
        ]);
        self::assertResponseStatusCodeSame(422);

        self::assertSame(3, $this->rows());
    }

    public function testABrokenRruleIs422(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed(['rrule' => 'FREQ=SOMETIMES']));

        self::assertResponseStatusCodeSame(422);
        self::assertSame(3, $this->rows());
    }

    public function testAPeriodHasNoHolidayField(): void
    {
        $this->login();

        // MAG-824: a period has no type, public holiday included — the field is gone, a client sending it is ignored.
        $this->send('POST', '/api/periods', $this->timed(['name' => 'Toussaint', 'holiday' => 'france']));

        self::assertResponseStatusCodeSame(201);
        self::assertArrayNotHasKey('holiday', $this->response());
        self::assertSame(4, $this->rows());
    }

    public function testAnotherUsersAgendaIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed(['agenda' => '/api/agendas/'.$this->id('other_agenda')]));

        self::assertContains($this->client->getResponse()->getStatusCode(), [400, 404]);
        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testExpandListsEachFortnightlyOccurrenceInTheRange(): void
    {
        $this->login();

        $this->send('GET', '/api/periods?'.http_build_query(['expand' => 'true'] + self::SIX_WEEKS));

        self::assertResponseIsSuccessful();
        $members = $this->response()['member'];
        $garde = array_values(array_filter($members, static fn (array $p): bool => 'Garde de Léo' === $p['name']));
        self::assertSame(
            ['2037-03-06T18:00:00+01:00', '2037-03-20T18:00:00+01:00', '2037-04-03T18:00:00+02:00'],
            array_map(static fn (array $p): string => (new \DateTimeImmutable($p['startAt']))->setTimezone(new \DateTimeZone('Europe/Paris'))->format('c'), $garde),
        );
        self::assertSame(array_fill(0, 3, $this->id('garde')), array_column($garde, 'id'));
        // The holidays begin on the range's last day; the other user's period stays theirs.
        self::assertContains('Vacances de printemps', array_column($members, 'name'));
        self::assertNotContains('Période secrète', array_column($members, 'name'));
        self::assertCount(4, $members);
    }

    public function testExpandLeavesOutWhatOnlyTouchesTheRange(): void
    {
        $this->login();

        // The 2nd occurrence ends on the 27th at 18:00 and the 3rd starts on the 3rd of April: neither meets [27th 18:00, 3rd 18:00).
        $this->send('GET', '/api/periods?'.http_build_query([
            'expand' => 'true',
            'endAt[after]' => '2037-03-27T18:00:00+01:00',
            'startAt[before]' => '2037-04-03T18:00:00+02:00',
        ]));

        self::assertResponseIsSuccessful();
        self::assertSame([], $this->response()['member']);
    }

    public function testExpandWithoutBoundsIs400(): void
    {
        $this->login();

        $this->send('GET', '/api/periods?'.http_build_query(['expand' => 'true', 'endAt[after]' => '2037-03-01']));

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('startAt[before]', (string) $this->client->getResponse()->getContent());
    }

    public function testAnotherUsersPeriodIs404(): void
    {
        $this->login();
        $iri = '/api/periods/'.$this->id('secret');

        $this->send('GET', $iri);
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $iri, ['name' => 'Volée']);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $iri);
        self::assertResponseStatusCodeSame(404);

        self::assertSame('Période secrète', $this->reload('secret')?->getName());
    }

    public function testCreateUpdateDeletePublishOnPeriodsAndIndex(): void
    {
        $this->login();

        $this->send('POST', '/api/periods', $this->timed());
        self::assertResponseStatusCodeSame(201);
        $id = $this->response()['id'];
        $this->assertMercureUpdatePublished(Period::COLLECTION.'/'.$id);
        $this->assertElasticsearchIndexDispatchedFor(Period::class, $id);

        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->send('PATCH', '/api/periods/'.$this->id('garde'), ['name' => 'Garde', 'rrule' => null]);
        self::assertResponseIsSuccessful();
        $garde = $this->reload('garde');
        self::assertSame('Garde', $garde?->getName());
        self::assertNull($garde?->getRrule());
        self::assertEquals(new \DateTimeImmutable('2037-03-06T18:00:00+01:00'), $garde?->getStartAt());
        $this->assertMercureUpdatePublished(Period::COLLECTION.'/'.$this->id('garde'));
        $this->assertElasticsearchIndexDispatchedFor(Period::class, $this->id('garde'));

        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->send('DELETE', '/api/periods/'.$this->id('vacances'));
        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->reload('vacances'));
        $this->assertMercureDeletePublished(Period::COLLECTION.'/'.$this->id('vacances'));
        $this->assertElasticsearchDeleteDispatched('periods');
    }

    public function testPatchCanMoveAPeriodToDays(): void
    {
        $this->login();

        $this->send('PATCH', '/api/periods/'.$this->id('garde'), ['allDay' => true, 'startAt' => null, 'endAt' => null, 'startDate' => '2037-03-07', 'endDate' => '2037-03-09']);

        self::assertResponseIsSuccessful();
        $garde = $this->reload('garde');
        self::assertTrue($garde?->isAllDay());
        self::assertNull($garde?->getStartAt());
        self::assertSame('2037-03-09', $garde?->getEndDate()?->format('Y-m-d'));
    }
}
