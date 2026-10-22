<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FinanceDashboardRefreshTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The monthly review's appointment (MAG-456): laid down in a « Finance » agenda
 * the first time the user opens their finances, on the 25th at 20:00, and
 * never again once that agenda exists — the user may move or delete it.
 */
class FinanceReviewAppointmentTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use FinanceDashboardRefreshTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock('2026-10-19 18:30:00 UTC'));
        $this->loadFixtures('review_progress.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function user(): User
    {
        $this->em()->clear();

        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function get(string $endpoint): void
    {
        $this->client->request('GET', $endpoint, [], [], $this->authHeaders());
    }

    /** @return list<Event> */
    private function appointments(): array
    {
        $agenda = $this->em()->getRepository(Agenda::class)->findOneBy(['user' => $this->user(), 'module' => Agenda::MODULE_FINANCE]);

        return null === $agenda ? [] : $this->em()->getRepository(Event::class)->findBy(['agenda' => $agenda]);
    }

    /** @return iterable<string, array{string}> */
    public static function financeReads(): iterable
    {
        yield 'the review progress' => ['/api/finance/review/progress?month=2026-10'];
        yield 'the month balance' => ['/api/finance/month-balance?month=2026-10'];
    }

    #[DataProvider('financeReads')]
    public function testOpeningTheFinancesLaysTheAppointmentDownOnce(string $endpoint): void
    {
        $this->get($endpoint);
        self::assertResponseIsSuccessful();

        $agenda = $this->em()->getRepository(Agenda::class)->findOneBy(['user' => $this->user(), 'module' => Agenda::MODULE_FINANCE]);
        self::assertNotNull($agenda);
        self::assertSame('Finance', $agenda->getName());
        self::assertSame('#7FD1AE', $agenda->getColor());
        self::assertFalse($agenda->isDefault());
        self::assertNull($agenda->getGoogleCalendarId(), 'internal: never synced with Google');

        $appointments = $this->appointments();
        self::assertCount(1, $appointments);
        $event = $appointments[0];
        self::assertSame('Revue du mois', $event->getSummary());
        self::assertSame('FREQ=MONTHLY;BYMONTHDAY=25', $event->getRrule());
        self::assertSame(Event::ACTIVITY_FINANCE_REVIEW, $event->getActivity());
        self::assertSame('Europe/Paris', $event->getTimeZone());
        $paris = new \DateTimeZone('Europe/Paris');
        self::assertSame('2026-10-25 20:00', $event->getStartAt()?->setTimezone($paris)->format('Y-m-d H:i'));
        self::assertSame('2026-10-25 21:00', $event->getEndAt()?->setTimezone($paris)->format('Y-m-d H:i'));

        // Through the bus: the open agendas hear of it, and the search index holds it.
        $this->assertMercurePublishedOnce('/api/events/'.$event->getId());
        $this->assertMercurePublishedOnce('/api/agendas/'.$agenda->getId());
        $this->assertElasticsearchIndexDispatchedFor(Event::class, (string) $event->getId());

        $this->resetMercure();
        $this->get($endpoint);
        self::assertResponseIsSuccessful();
        self::assertCount(1, $this->appointments(), 'read again, the same appointment');
        $this->assertMercureUpdateCount(0);
    }

    public function testReadingTheDashboardLaysNothingDown(): void
    {
        $this->get('/api/finance/dashboard?year=2026&month=10');
        self::assertResponseIsSuccessful();

        self::assertSame([], $this->appointments(), 'a GET writes nothing: the agenda waits for the dashboard rebuild');
    }

    public function testTheDashboardRebuildLaysTheAppointmentDownOnce(): void
    {
        $this->drainDashboardRefreshes();
        self::assertCount(1, $this->appointments());

        $this->drainDashboardRefreshes();
        self::assertCount(1, $this->appointments(), 'a second rebuild finds it there');
    }

    public function testADeletedAppointmentIsNotLaidDownAgain(): void
    {
        $this->get('/api/finance/review/progress');
        [$event] = $this->appointments();
        $this->client->request('DELETE', '/api/events/'.$event->getId(), [], [], $this->authHeaders());
        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->appointments());

        foreach (self::financeReads() as [$endpoint]) {
            $this->get($endpoint);
            self::assertResponseIsSuccessful();
        }

        self::assertSame([], $this->appointments(), 'the user deleted it: it does not come back');
    }

    public function testTheAppointmentIsTheUsersAlone(): void
    {
        $this->get('/api/finance/review/progress');

        $other = $this->em()->find(User::class, $this->getFixture('other_user')->getId());
        self::assertNull($this->em()->getRepository(Agenda::class)->findOneBy(['user' => $other, 'module' => Agenda::MODULE_FINANCE]));
    }

    public function testAnUnauthenticatedReadLaysNothingDown(): void
    {
        $this->client->request('GET', '/api/finance/review/progress');
        self::assertResponseStatusCodeSame(401);

        self::assertSame([], $this->em()->getRepository(Agenda::class)->findBy(['module' => Agenda::MODULE_FINANCE]));
    }
}
