<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Cookbook\Entity\Meal;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A meal always goes in the meals' module agenda, which Google never sees (MAG-324).
 *
 * The case the owner hit: the week view looked for an agenda called « Repas »,
 * fell back on the default one, and the lunch of the 7th landed in « Défaut »,
 * which is synced with Google.
 */
class MealAgendaTest extends WebTestCase
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
        $this->loadFixtures('MealAgendaTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    public function testTheFirstMealCreatesTheModuleAgendaAndGoesInIt(): void
    {
        $default = $this->getFixture('default_agenda');

        // Exactly what the admin sent: the default agenda, since no "Repas" one existed.
        $data = $this->postMeal(['agenda' => '/api/agendas/'.$default->getId()]);

        self::assertResponseStatusCodeSame(201);

        $moduleAgendas = $this->moduleAgendasOf('test_user');
        self::assertCount(1, $moduleAgendas);
        $module = $moduleAgendas[0];
        self::assertSame('Repas', $module->getName());
        self::assertFalse($module->isDefault());
        self::assertNull($module->getGoogleCalendarId());
        self::assertSame('/api/agendas/'.$module->getId(), $data['agenda']);

        self::assertSame((string) $module->getId(), (string) $this->storedMeal($data['id'])->getAgenda()->getId());
        self::assertCount(0, $this->eventsIn($default), 'The default agenda received no event.');
        self::assertCount(0, $this->eventsIn($this->getFixture('user_named_repas')), 'An agenda merely called « Repas » is not the module one.');

        $this->assertNothingWasSentToGoogle();
        $this->assertMercureUpdatePublished('/meals/');
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Meal::class);
        $this->assertElasticsearchIndexDispatchedFor(Agenda::class, (string) $module->getId());
    }

    public function testAMealPostedWithoutAnyAgendaIsFiledInTheModuleAgenda(): void
    {
        $data = $this->postMeal([]);

        self::assertResponseStatusCodeSame(201);
        $stored = $this->storedMeal($data['id']);
        self::assertSame('cookbook', $stored->getAgenda()->getModule());
        $this->assertNothingWasSentToGoogle();
    }

    public function testTheAgendaSentByAClientIsIgnoredOnceTheModuleAgendaExists(): void
    {
        $first = $this->postMeal([]);
        $moduleId = (string) $this->storedMeal($first['id'])->getAgenda()->getId();
        $this->resetAsyncTransport();

        $second = $this->postMeal([
            'date' => '2026-10-08',
            'agenda' => '/api/agendas/'.$this->getFixture('default_agenda')->getId(),
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($moduleId, (string) $this->storedMeal($second['id'])->getAgenda()->getId());
        self::assertCount(1, $this->moduleAgendasOf('test_user'), 'No second module agenda.');
        $this->assertNothingWasSentToGoogle();
    }

    public function testRenamingTheModuleAgendaDoesNotBreakTheNextMeals(): void
    {
        $first = $this->postMeal([]);
        $module = $this->storedMeal($first['id'])->getAgenda();

        $this->client->request('PATCH', '/api/agendas/'.$module->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['name' => 'Mes repas'], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $second = $this->postMeal(['date' => '2026-10-08']);

        self::assertResponseStatusCodeSame(201);
        $moduleAgendas = $this->moduleAgendasOf('test_user');
        self::assertCount(1, $moduleAgendas);
        self::assertSame('Mes repas', $moduleAgendas[0]->getName());
        self::assertSame((string) $module->getId(), (string) $this->storedMeal($second['id'])->getAgenda()->getId());
    }

    public function testAnAgendaCalledRepasBeforeTheAttributeExistedIsTakenOverNotDuplicated(): void
    {
        $this->authenticateAsUser($this->getFixture('legacy_user'));
        $legacy = $this->getFixture('legacy_repas_agenda');

        $data = $this->postMeal([]);

        self::assertResponseStatusCodeSame(201);
        $moduleAgendas = $this->moduleAgendasOf('legacy_user');
        self::assertCount(1, $moduleAgendas);
        self::assertSame((string) $legacy->getId(), (string) $moduleAgendas[0]->getId());
        self::assertSame((string) $legacy->getId(), (string) $this->storedMeal($data['id'])->getAgenda()->getId());
        self::assertCount(1, $this->em()->getRepository(Agenda::class)->findBy([
            'user' => $this->getFixture('legacy_user')->getId(),
        ]), 'The user did not end up with two « Repas ».');
    }

    public function testAnOrdinaryEventCannotBeCreatedInTheModuleAgenda(): void
    {
        $created = $this->postMeal([]);
        $moduleId = (string) $this->storedMeal($created['id'])->getAgenda()->getId();

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'summary' => 'Dentiste',
            'startAt' => '2026-10-08T09:00:00+00:00',
            'endAt' => '2026-10-08T10:00:00+00:00',
            'agenda' => '/api/agendas/'.$moduleId,
        ], JSON_THROW_ON_ERROR));

        self::assertGreaterThanOrEqual(400, $this->client->getResponse()->getStatusCode());
        self::assertCount(1, $this->eventsIn($this->moduleAgendasOf('test_user')[0]), 'Only the meal is in the module agenda.');
    }

    public function testAnotherUsersModuleAgendaIsNeverUsed(): void
    {
        $data = $this->postMeal([]);

        $other = $this->getFixture('other_module_agenda');
        self::assertNotSame((string) $other->getId(), (string) $this->storedMeal($data['id'])->getAgenda()->getId());
        self::assertCount(0, $this->eventsIn($other));
    }

    public function testAMealCannotBePatchedIntoAnotherAgenda(): void
    {
        $created = $this->postMeal([]);
        $moduleId = (string) $this->storedMeal($created['id'])->getAgenda()->getId();

        $this->client->request('PATCH', '/api/meals/'.$created['id'], [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'agenda' => '/api/agendas/'.$this->getFixture('default_agenda')->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame($moduleId, (string) $this->storedMeal($created['id'])->getAgenda()->getId());
        $this->assertNothingWasSentToGoogle();
    }

    public function testAMealCannotBeMovedOutOfTheModuleAgendaThroughTheEventDoor(): void
    {
        $created = $this->postMeal([]);
        $moduleId = (string) $this->storedMeal($created['id'])->getAgenda()->getId();
        $this->resetAsyncTransport();

        $this->client->request('PATCH', '/api/events/'.$created['id'], [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'agenda' => '/api/agendas/'.$this->getFixture('default_agenda')->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertSame($moduleId, (string) $this->storedMeal($created['id'])->getAgenda()->getId());
        $this->assertNothingWasSentToGoogle();
    }

    /** @param array<string, mixed> $body */
    private function postMeal(array $body): array
    {
        $body += ['date' => '2026-10-07', 'slot' => 'lunch', 'summary' => 'Déjeuner'];

        $this->client->request('POST', '/api/meals', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));

        $decoded = json_decode((string) $this->client->getResponse()->getContent(), true);

        return \is_array($decoded) ? $decoded : [];
    }

    private function em(): EntityManagerInterface
    {
        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function storedMeal(string $id): Meal
    {
        return $this->em()->getRepository(Meal::class)->find($id);
    }

    /** @return list<Event> */
    private function eventsIn(Agenda $agenda): array
    {
        return $this->em()->getRepository(Event::class)->findBy(['agenda' => $agenda->getId()]);
    }

    /** @return list<Agenda> */
    private function moduleAgendasOf(string $fixture): array
    {
        return $this->em()->getRepository(Agenda::class)->findBy([
            'user' => $this->getFixture($fixture)->getId(),
            'module' => 'cookbook',
        ]);
    }

    private function assertNothingWasSentToGoogle(): void
    {
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            self::assertNotInstanceOf(PushEventToGoogleCommand::class, $envelope->getMessage());
        }
    }
}
