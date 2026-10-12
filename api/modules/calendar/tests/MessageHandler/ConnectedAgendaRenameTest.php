<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Google\Service\Calendar\CalendarListEntry;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Mcp\Tool\ManageAgendasTool;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Renaming an agenda connected to Google goes to Google, whichever door asked (MAG-385).
 *
 * « Renomme l'agenda Garde Ael en "Ael" » was answered « c'est fait », and the next
 * sync put « Garde Ael » back: Google owns the name on the way in, and nothing sent
 * the new one on the way out.
 */
class ConnectedAgendaRenameTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    /** What Google answers for the calendar's name, and the only thing a rename changes there. */
    private string $googleName = 'Garde Ael';
    private bool $googleIsPrimary = false;
    private ?\Throwable $googleRefuses = null;
    /** @var list<string> the names Google was sent, in order */
    private array $sentNames = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->googleName = 'Garde Ael';
        $this->googleIsPrimary = false;
        $this->googleRefuses = null;
        $this->sentNames = [];
        $this->loadFixtures('ConnectedAgendaRenameTest.yaml');
        $this->stubGoogle();
    }

    private function stubGoogle(): void
    {
        $apiClient = $this->createStub(GoogleCalendarApiClient::class);
        $apiClient->method('getCalendarListEntry')->willReturnCallback(function (): CalendarListEntry {
            $entry = new CalendarListEntry();
            $entry->setId('garde-ael@group.calendar.google.com');
            $entry->setSummary($this->googleName);
            $entry->setPrimary($this->googleIsPrimary);

            return $entry;
        });
        $apiClient->method('renameCalendar')->willReturnCallback(function (User $user, string $calendarId, string $name): void {
            if (null !== $this->googleRefuses) {
                throw $this->googleRefuses;
            }
            $this->sentNames[] = $name;
            $this->googleName = $name;
        });
        $apiClient->method('listEvents')->willReturn(['events' => [], 'nextSyncToken' => 'sync-token', 'nextPageToken' => null]);

        self::getContainer()->set(GoogleCalendarApiClient::class, $apiClient);
    }

    private function rename(string $fixture, string $name): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture($fixture)->getId(),
            name: $name,
        ));
    }

    private function stored(string $fixture): Agenda
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->find($this->getFixture($fixture)->getId());
    }

    private function sync(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $agenda = $em->getRepository(Agenda::class)->find($this->getFixture('connected_agenda')->getId());
        self::getContainer()->get(GoogleCalendarSyncService::class)->pullFromGoogle($agenda);
    }

    public function testARenamedConnectedAgendaKeepsItsNameAfterTheNextSync(): void
    {
        $this->rename('connected_agenda', 'Ael');
        $this->sync();

        self::assertSame(['Ael'], $this->sentNames, 'Google should have received the new name as summary.');
        self::assertSame('Ael', $this->stored('connected_agenda')->getName());
    }

    public function testWhenGoogleRefusesTheNameGoesBackAndTheCallerIsTold(): void
    {
        $this->googleRefuses = new \RuntimeException('403 forbidden');

        try {
            $this->rename('connected_agenda', 'Ael');
            self::fail('The caller should have been told the rename did not reach Google.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(\DomainException::class, $e->getPrevious());
            self::assertStringContainsString('Google', $e->getPrevious()->getMessage());
        }

        self::assertSame('Garde Ael', $this->stored('connected_agenda')->getName());
        $this->assertMercureUpdatePublished('/agendas/');
    }

    public function testAnAgendaNotConnectedToGoogleIsRenamedAsBefore(): void
    {
        $this->rename('local_agenda', 'Mon agenda');

        self::assertSame([], $this->sentNames);
        self::assertSame('Mon agenda', $this->stored('local_agenda')->getName());
    }

    public function testTheNameGoogleSentIsNotSentBack(): void
    {
        $this->googleName = 'Renommé chez Google';
        $this->sync();

        self::assertSame('Renommé chez Google', $this->stored('connected_agenda')->getName());
        self::assertSame([], $this->sentNames, 'A name that came from Google must not go back to Google.');
    }

    public function testThePrimaryCalendarCannotBeRenamed(): void
    {
        $this->googleIsPrimary = true;
        $this->googleName = 'fixture@example.com';

        try {
            $this->rename('connected_agenda', 'Ael');
            self::fail('The caller should have been told the primary calendar keeps its name.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(\DomainException::class, $e->getPrevious());
        }

        self::assertSame([], $this->sentNames);
        self::assertSame('Garde Ael', $this->stored('connected_agenda')->getName());
    }

    public function testManageAgendasUpdateRenamesInGoogleToo(): void
    {
        $this->loginFixtureUser();

        $data = json_decode((self::getContainer()->get(ManageAgendasTool::class))('update', agendaId: (string) $this->getFixture('connected_agenda')->getId(), name: 'Ael'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Ael', $data['agenda']['name']);
        self::assertSame(['Ael'], $this->sentNames);
    }

    public function testManageAgendasUpdateTellsMaggieWhenGoogleRefuses(): void
    {
        $this->loginFixtureUser();
        $this->googleRefuses = new \RuntimeException('403 forbidden');

        $data = json_decode((self::getContainer()->get(ManageAgendasTool::class))('update', agendaId: (string) $this->getFixture('connected_agenda')->getId(), name: 'Ael'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('could not be renamed in Google', $data['error']);
        self::assertStringContainsString('Garde Ael', $data['error']);
        self::assertSame('Garde Ael', $this->stored('connected_agenda')->getName());
    }
}
