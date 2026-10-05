<?php

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Entity\User;
use Maggie\Core\Service\RecetteAccount;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * The Recette account has no Google tokens and a made-up googleId (MAG-249): the
 * sync entry points must select no work for it, so nothing ever calls Google.
 */
final class RecetteAccountGoogleInertTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    public function testTheGoogleSyncSelectsNothingForTheRecetteAccount(): void
    {
        self::bootKernel();
        $this->purgeDatabase();
        $application = new Application(self::$kernel);
        (new CommandTester($application->find('app:recette:token')))->execute([]);

        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $em->getRepository(User::class)->findOneBy(['email' => RecetteAccount::EMAIL]);
        $em->persist((new Agenda())->setName('Recette')->setUser($user));
        $em->flush();

        self::assertFalse($user->hasGoogleCalendarTokens());

        $events = new CommandTester($application->find('maggie:google-calendar:sync'));
        $events->execute([]);
        $tasks = new CommandTester($application->find('maggie:google-calendar:sync'));
        $tasks->execute(['--tasks' => true]);

        $events->assertCommandIsSuccessful();
        $tasks->assertCommandIsSuccessful();
        self::assertStringContainsString('Synced tasks for 0 user(s)', $tasks->getDisplay());
        self::assertStringContainsString('Synced 0 agenda(s)', $events->getDisplay());
    }
}
