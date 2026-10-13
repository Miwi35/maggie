<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The admin and the mobile app rename through PATCH: Google gets the name, or they get a clear error (MAG-385). */
class ConnectedAgendaRenameApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;
    /** @var list<string> */
    private array $sentNames = [];

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->sentNames = [];
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/ConnectedAgendaRenameTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function stubGoogle(bool $refuses): void
    {
        $apiClient = $this->createStub(GoogleCalendarApiClient::class);
        $apiClient->method('getCalendarListEntry')->willReturn(new \Google\Service\Calendar\CalendarListEntry());
        $apiClient->method('renameCalendar')->willReturnCallback(function ($user, $calendarId, string $name) use ($refuses): void {
            if ($refuses) {
                throw new \RuntimeException('403 forbidden');
            }
            $this->sentNames[] = $name;
        });
        self::getContainer()->set(GoogleCalendarApiClient::class, $apiClient);
    }

    private function rename(string $fixture, string $name): void
    {
        $this->client->request('PATCH', '/api/agendas/'.$this->getFixture($fixture)->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['name' => $name], JSON_THROW_ON_ERROR));
    }

    private function storedName(string $fixture): string
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->find($this->getFixture($fixture)->getId())->getName();
    }

    public function testRenamingAConnectedAgendaSendsTheNameToGoogle(): void
    {
        $this->stubGoogle(refuses: false);

        $this->rename('connected_agenda', 'Ael');

        self::assertResponseIsSuccessful();
        self::assertSame(['Ael'], $this->sentNames);
        self::assertSame('Ael', $this->storedName('connected_agenda'));
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testWhenGoogleRefusesTheAnswerIsAnErrorAndTheNameIsUnchanged(): void
    {
        $this->stubGoogle(refuses: true);

        $this->rename('connected_agenda', 'Ael');

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('could not be renamed in Google', (string) $this->client->getResponse()->getContent());
        self::assertSame('Garde Ael', $this->storedName('connected_agenda'));
        // What the other screens read is the reverted name, not the one that was refused.
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
        $published = $this->mercurePayloadsOn('/api/agendas/'.$this->getFixture('connected_agenda')->getId());
        self::assertSame('Garde Ael', end($published)['name'] ?? null);
    }

    public function testRenamingAnAgendaNotConnectedToGoogleNeedsNoGoogle(): void
    {
        $this->stubGoogle(refuses: true);

        $this->rename('local_agenda', 'Mon agenda');

        self::assertResponseIsSuccessful();
        self::assertSame('Mon agenda', $this->storedName('local_agenda'));
    }
}
