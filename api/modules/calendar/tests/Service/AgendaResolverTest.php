<?php

namespace Maggie\Calendar\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Turning what a model passes as "the agenda" into one of the caller's own (MAG-230), and
 * the distinction MAG-150 needs from it: a reference matching *nothing* is a hint the
 * deduction may read, while a reference matching *several* agendas is an ambiguity in the
 * agendas themselves that no deduction may resolve.
 *
 * The world is `AgendaByNameToolsTest.yaml`, shared rather than copied: the two suites
 * assert on the same agendas, and two copies would drift.
 */
class AgendaResolverTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(dirname(__DIR__).'/Mcp/fixtures/AgendaByNameToolsTest.yaml');
    }

    private function resolver(): AgendaResolver
    {
        return self::getContainer()->get(AgendaResolver::class);
    }

    private function user(string $ref = 'test_user'): User
    {
        $user = $this->getFixture($ref);
        self::assertInstanceOf(User::class, $user);

        return $user;
    }

    private function agenda(string $ref): Agenda
    {
        $agenda = $this->getFixture($ref);
        self::assertInstanceOf(Agenda::class, $agenda);

        return $agenda;
    }

    /** @return iterable<string, array{string, string}> */
    public static function spokenNames(): iterable
    {
        yield 'exact' => ['Concerts', 'Concerts'];
        yield 'lower case' => ['concerts', 'Concerts'];
        yield 'padded and upper case' => ['  CONCERTS ', 'Concerts'];
        yield 'accents dropped' => ['soirees', 'Soirées'];
        yield 'accents added' => ['Soirées', 'Soirées'];
    }

    #[DataProvider('spokenNames')]
    public function testFindExactTakesTheNameAsItWasSpoken(string $spoken, string $expected): void
    {
        self::assertSame($expected, $this->resolver()->findExact($this->user(), $spoken)?->getName());
    }

    public function testFindExactTakesAnId(): void
    {
        $concerts = $this->agenda('concerts_agenda');

        self::assertSame(
            (string) $concerts->getId(),
            (string) $this->resolver()->findExact($this->user(), (string) $concerts->getId())?->getId(),
        );
    }

    /** @return iterable<string, array{string}> */
    public static function referencesMatchingNothing(): iterable
    {
        yield 'a name the user has no agenda for' => ['Théâtre'];
        yield 'an id no agenda carries' => ['01JZZZZZZZZZZZZZZZZZZZZZZZ'];
        yield 'nothing at all' => ['   '];
    }

    #[DataProvider('referencesMatchingNothing')]
    public function testFindExactReturnsNullRatherThanGuessing(string $reference): void
    {
        self::assertNull($this->resolver()->findExact($this->user(), $reference));
    }

    public function testFindExactIgnoresAnotherUsersAgendaByIdAndByName(): void
    {
        $resolver = $this->resolver();
        $secret = $this->agenda('other_secret_agenda');

        self::assertNull($resolver->findExact($this->user(), (string) $secret->getId()));
        self::assertNull($resolver->findExact($this->user(), 'Agenda secret du voisin'));
    }

    public function testFindExactRefusesToPickBetweenTwoAgendasOfTheSameName(): void
    {
        $this->expectException(\DomainException::class);
        $this->expectExceptionMessageMatches('/Several agendas are named "SPORT".+Available agendas/s');

        $this->resolver()->findExact($this->user(), 'SPORT');
    }

    public function testResolveReturnsTheAgendaWhenThereIsOne(): void
    {
        self::assertSame('Concerts', $this->resolver()->resolve($this->user(), 'concerts')->getName());
    }

    public function testResolveRefusesAReferenceMatchingNothing(): void
    {
        $this->expectException(\DomainException::class);
        $this->expectExceptionMessage('No agenda has the id or name "Théâtre".');

        $this->resolver()->resolve($this->user(), 'Théâtre');
    }

    public function testTheUnknownReferenceAnswerNamesOnlyTheCallersAgendas(): void
    {
        $message = $this->resolver()->unknownReference($this->user(), ' Théâtre ')->getMessage();

        self::assertStringContainsString('No agenda has the id or name "Théâtre"', $message);
        foreach (['Main', 'Concerts', 'Soirées', 'Sport'] as $name) {
            self::assertStringContainsString($name, $message);
        }
        self::assertStringContainsString((string) $this->agenda('concerts_agenda')->getId(), $message);
        self::assertStringNotContainsString('Agenda secret du voisin', $message);
        self::assertStringNotContainsString((string) $this->agenda('other_secret_agenda')->getId(), $message);
    }

    public function testDescribingTheAgendasOfAUserWhoHasNoneSaysSo(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement('DELETE FROM event');
        $em->getConnection()->executeStatement('DELETE FROM agenda');
        $em->clear();

        self::assertSame('The user has no agenda yet.', $this->resolver()->describeFor($this->user()));
    }
}
