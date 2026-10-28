<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\CreateNotificationCommand;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The owner deletes an event through the door he uses (REST): the « Pars maintenant » alert about
 * its trip stops being worth saying on the spot, not at the event's start (MAG-679 recette).
 */
class TripRemovalExpiresAlertTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    public function testDeletingTheEventExpiresItsDepartureAlertNow(): void
    {
        $startsAt = new \DateTimeImmutable('+1 hour');
        $event = $this->em()->find(Event::class, (string) $this->getFixture('dentist')->getId());
        self::assertNotNull($event);
        // An event of today: its removal also raises EventTripsChangedEvent, whose handlers flush again.
        $event->setStartAt($startsAt)->setEndAt($startsAt->modify('+1 hour'));
        $this->em()->flush();
        // Moving it filed a replanning; none must be pending when it is deleted, or the deletion files none.
        $this->em()->createQuery('DELETE FROM '.Proaction::class)->execute();
        $trip = $this->seedTrip($event);
        $this->alert('Pars maintenant pour Dentiste', '/api/trips/'.$trip, $startsAt);

        $this->client->request('DELETE', '/api/events/'.$event->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        $this->em()->clear();
        $alert = $this->em()->getRepository(Notification::class)->findOneBy(['title' => 'Pars maintenant pour Dentiste']);
        self::assertNotNull($alert);
        self::assertLessThanOrEqual(new \DateTimeImmutable(), $alert->getExpiresAt(), 'Gone is gone: the alert expires at the deletion, not at the start of the event.');
        self::assertNull($alert->getReadAt(), 'It stays in the bell as history.');
        self::assertCount(1, $this->em()->getRepository(Proaction::class)->findAll(), 'The deletion filed a replanning: the flush its handlers make is what this test is about.');
    }

    private function seedTrip(Event $event): string
    {
        $trip = (new Trip($event))->apply(new TripEstimate(
            mode: TripMode::Walk,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: [new RouteLeg(TripMode::Walk, 240)],
            durationMinutes: 4,
            departAt: new \DateTimeImmutable('-1 minute'),
            navigationUrl: 'https://waze.com/ul?ll=48.119,-1.6672&navigate=yes',
            parkingKnown: true,
            carLatitude: null,
            carLongitude: null,
        ), TripDecider::Maggie, new \DateTimeImmutable());
        $this->em()->persist($trip);
        $this->em()->flush();

        return (string) $trip->getId();
    }

    private function alert(string $title, string $iri, \DateTimeImmutable $expiresAt): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: 'departure',
            title: $title,
            relatedEntityIri: $iri,
            userId: (string) $this->getFixture('test_user')->getId(),
            expiresAt: $expiresAt,
        ));
        $this->em()->clear();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
