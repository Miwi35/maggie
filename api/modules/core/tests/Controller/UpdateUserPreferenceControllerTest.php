<?php

namespace Maggie\Core\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\UserPreference;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class UpdateUserPreferenceControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
    }

    /** @param array<string, mixed> $body */
    private function patch(array $body): void
    {
        $this->client->request('PATCH', '/api/user_preferences/me', [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('PATCH', '/api/user_preferences/me', [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['theme' => 'dark'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchPersistsTheChange(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['theme' => 'dark']);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('dark', $data['theme']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(UserPreference::class, $this->getFixture('preference')->getId());
        self::assertSame('dark', $stored->getTheme());
    }

    public function testFieldsLeftOutKeepTheirValue(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['theme' => 'dark']);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('fr', $data['locale']);
        self::assertSame('Europe/Paris', $data['timezone']);
        self::assertSame(['agenda-1'], $data['enabledAgendaIds']);
        self::assertTrue($data['notificationsEnabled']);
    }

    public function testPatchAcceptsEveryPreference(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch([
            'theme' => 'light',
            'locale' => 'en',
            'timezone' => 'Europe/Zurich',
            'defaultCalendarView' => 'week',
            'enabledAgendaIds' => ['a', 'b'],
            'notificationsEnabled' => false,
        ]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(UserPreference::class, $this->getFixture('preference')->getId());
        self::assertSame('light', $stored->getTheme());
        self::assertSame('en', $stored->getLocale());
        self::assertSame('Europe/Zurich', $stored->getTimezone());
        self::assertSame('week', $stored->getDefaultCalendarView());
        self::assertSame(['a', 'b'], $stored->getEnabledAgendaIds());
        self::assertFalse($stored->isNotificationsEnabled());
    }

    public function testPatchCreatesThePreferenceOnFirstUse(): void
    {
        $this->loadFixtures('user.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['theme' => 'dark']);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(UserPreference::class)->findAll());
    }

    public function testATypeMismatchIsRejected(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['notificationsEnabled' => 'yes']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAgendaIdsMustBeStrings(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['enabledAgendaIds' => [1, 2]]);

        self::assertResponseStatusCodeSame(400);
    }

    /**
     * Every preference column is NOT NULL: there is nothing to clear, so an
     * explicit JSON null is rejected instead of silently ignored.
     */
    public function testAnExplicitNullOnARequiredPreferenceIsRejectedAndChangesNothing(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach (['theme', 'locale', 'timezone', 'defaultCalendarView', 'notificationsEnabled'] as $field) {
            $this->patch([$field => null]);

            self::assertResponseStatusCodeSame(400, "null on {$field} must be rejected");
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(UserPreference::class, $this->getFixture('preference')->getId());
        self::assertSame('system', $stored->getTheme());
        self::assertSame('fr', $stored->getLocale());
        self::assertSame('Europe/Paris', $stored->getTimezone());
        self::assertSame('month', $stored->getDefaultCalendarView());
        self::assertTrue($stored->isNotificationsEnabled());
    }

    public function testAnEmptyAgendaListCanBeSaved(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['enabledAgendaIds' => []]);

        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(UserPreference::class, $this->getFixture('preference')->getId());
        self::assertSame([], $stored->getEnabledAgendaIds());
    }

    public function testTheDefaultCityCanBeSetTrimmedAndCleared(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $id = $this->getFixture('preference')->getId();

        $this->patch(['defaultCity' => '  Rennes ']);

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Rennes', $data['defaultCity']);
        $em->clear();
        self::assertSame('Rennes', $em->find(UserPreference::class, $id)->getDefaultCity());

        $this->patch(['theme' => 'dark']);
        $em->clear();
        self::assertSame('Rennes', $em->find(UserPreference::class, $id)->getDefaultCity(), 'left out keeps it');

        $this->patch(['defaultCity' => '']);

        self::assertResponseIsSuccessful();
        $em->clear();
        self::assertNull($em->find(UserPreference::class, $id)->getDefaultCity());
    }

    public function testTheDefaultCityMustBeAShortString(): void
    {
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->patch(['defaultCity' => 42]);
        self::assertResponseStatusCodeSame(400);

        $this->patch(['defaultCity' => str_repeat('a', 101)]);
        self::assertResponseStatusCodeSame(400);
    }
}
