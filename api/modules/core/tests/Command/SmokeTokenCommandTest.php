<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Command\SmokeTokenCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

final class SmokeTokenCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purgeDatabase();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application(self::$kernel);

        $this->tester = new CommandTester($application->find('app:smoke:token'));
    }

    public function testItCreatesTheTechnicalAccountOnFirstUse(): void
    {
        self::assertSame(0, $this->countSmokeUsers());

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();

        $user = $this->smokeUser();
        self::assertNotNull($user, 'The technical account must exist once the command has run.');
        self::assertSame(SmokeTokenCommand::NAME, $user->getName());
        self::assertSame(SmokeTokenCommand::GOOGLE_ID, $user->getGoogleId());
        self::assertSame(['ROLE_USER'], $user->getRoles(), 'The technical account must not carry any privilege.');

        $this->assertMercureUpdatePublished('/users/');
        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testItReusesTheAccountOnTheNextRun(): void
    {
        $this->tester->execute([]);
        $firstId = (string) $this->smokeUser()->getId();

        $this->tester->execute([]);

        self::assertSame(1, $this->countSmokeUsers(), 'Every deploy runs the command; it must not pile up accounts.');
        self::assertSame($firstId, (string) $this->smokeUser()->getId());
    }

    public function testStdoutCarriesTheTokenAlone(): void
    {
        $this->tester->execute([]);

        $token = trim($this->tester->getDisplay());

        self::assertMatchesRegularExpression(
            '/^[\w-]+\.[\w-]+\.[\w-]+$/',
            $token,
            'The workflow captures stdout with $(…): anything but the JWT would make every request fail.',
        );
    }

    public function testTheTokenIdentifiesTheTechnicalAccountForTheApiTheAgentAndMcp(): void
    {
        $this->tester->execute([]);

        $claims = self::getContainer()->get(JWTTokenManagerInterface::class)
            ->parse(trim($this->tester->getDisplay()));

        // `username` is what the API firewall resolves, `sub` what the agent
        // and the MCP endpoint read: the suite crosses all three.
        self::assertSame(SmokeTokenCommand::EMAIL, $claims['username'] ?? null);
        self::assertSame((string) $this->smokeUser()->getId(), $claims['sub'] ?? null);
    }

    public function testItLeavesEveryOtherAccountAlone(): void
    {
        $this->loadFixtures('SmokeTokenCommandTest.yaml');

        $this->tester->execute([]);

        $owner = $this->entityManager()->getRepository(User::class)->findOneBy(['email' => 'owner@maggie.local']);
        self::assertNotNull($owner);
        self::assertSame('Owner', $owner->getName());
        self::assertSame(2, $this->entityManager()->getRepository(User::class)->count([]));
    }

    private function smokeUser(): ?User
    {
        $this->entityManager()->clear();

        return $this->entityManager()->getRepository(User::class)->findOneBy(['email' => SmokeTokenCommand::EMAIL]);
    }

    private function countSmokeUsers(): int
    {
        return $this->entityManager()->getRepository(User::class)->count(['email' => SmokeTokenCommand::EMAIL]);
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
