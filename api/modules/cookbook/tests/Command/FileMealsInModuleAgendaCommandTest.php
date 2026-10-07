<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Cookbook\Entity\Meal;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * Meals filed before the module agenda existed move into it, Google copy removed (MAG-324).
 */
class FileMealsInModuleAgendaCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('FileMealsInModuleAgendaCommandTest.yaml');

        $this->tester = new CommandTester((new Application(self::$kernel))->find('app:cookbook:file-meals-in-module-agenda'));
    }

    public function testEveryMealEndsInItsUsersModuleAgenda(): void
    {
        $defaultId = (string) $this->getFixture('default_agenda')->getId();
        $pushedId = (string) $this->getFixture('pushed_meal')->getId();
        $localId = (string) $this->getFixture('local_meal')->getId();
        $otherId = (string) $this->getFixture('other_users_meal')->getId();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();

        $userModule = $this->em()->getRepository(Agenda::class)->findOneBy([
            'user' => $this->getFixture('test_user')->getId(),
            'module' => 'cookbook',
        ]);
        self::assertNotNull($userModule, 'The module agenda was created for the user who had none.');
        self::assertSame('Repas', $userModule->getName());
        self::assertFalse($userModule->isDefault());
        self::assertNull($userModule->getGoogleCalendarId());

        foreach ([$pushedId, $localId] as $id) {
            self::assertSame((string) $userModule->getId(), (string) $this->meal($id)->getAgenda()->getId());
        }
        self::assertSame(
            (string) $this->getFixture('other_module_agenda')->getId(),
            (string) $this->meal($otherId)->getAgenda()->getId(),
            'A user who already had a module agenda (renamed) keeps using it.',
        );

        $remaining = $this->em()->createQuery(
            'SELECT COUNT(m.id) FROM '.Meal::class.' m JOIN m.agenda a WHERE a.module IS NULL OR a.module <> :module',
        )->setParameter('module', 'cookbook')->getSingleScalarResult();
        self::assertSame(0, (int) $remaining, 'No meal is left outside a module agenda.');

        $pushed = $this->meal($pushedId);
        self::assertNull($pushed->getGoogleEventId());
        self::assertNull($pushed->getGoogleEtag());

        $deletions = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            if ($envelope->getMessage() instanceof DeleteEventFromGoogleCommand) {
                $deletions[] = $envelope->getMessage();
            }
        }
        self::assertCount(1, $deletions, 'Only the meal Google held is deleted there.');
        self::assertSame($defaultId, $deletions[0]->agendaId);
        self::assertSame('google-event-1', $deletions[0]->googleEventId);

        $this->assertMercureUpdatePublished('/meals/');
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatchedFor(Meal::class, $pushedId);
        $this->assertElasticsearchIndexDispatchedFor(Agenda::class, (string) $userModule->getId());
    }

    public function testAnAgendaCalledRepasBeforeTheAttributeExistedIsTakenOver(): void
    {
        $legacyId = (string) $this->getFixture('legacy_repas_agenda')->getId();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        $agendas = $this->em()->getRepository(Agenda::class)->findBy(['user' => $this->getFixture('legacy_user')->getId()]);
        self::assertCount(1, $agendas, 'No second « Repas » next to the one the user already had.');
        self::assertSame($legacyId, (string) $agendas[0]->getId());
        self::assertSame('cookbook', $agendas[0]->getModule());
        self::assertSame($legacyId, (string) $this->meal((string) $this->getFixture('legacy_meal')->getId())->getAgenda()->getId());
    }

    public function testRunningItAgainChangesNothing(): void
    {
        $this->tester->execute([]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame([], $this->getAsyncTransport()->getSent());
        $this->assertMercureUpdateCount(0);
    }

    public function testDryRunChangesNothing(): void
    {
        $defaultId = (string) $this->getFixture('default_agenda')->getId();
        $pushedId = (string) $this->getFixture('pushed_meal')->getId();
        $this->resetAsyncTransport();

        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame($defaultId, (string) $this->meal($pushedId)->getAgenda()->getId());
        self::assertSame('google-event-1', $this->meal($pushedId)->getGoogleEventId());
        self::assertSame([], $this->getAsyncTransport()->getSent());
        self::assertNull($this->em()->getRepository(Agenda::class)->findOneBy([
            'user' => $this->getFixture('test_user')->getId(),
            'module' => 'cookbook',
        ]));
    }

    private function em(): EntityManagerInterface
    {
        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function meal(string $id): Meal
    {
        return $this->em()->getRepository(Meal::class)->find($id);
    }
}
