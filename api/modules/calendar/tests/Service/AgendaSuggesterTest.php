<?php

namespace Maggie\Calendar\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\AgendaCandidate;
use Maggie\Calendar\Service\AgendaChoiceKind;
use Maggie\Calendar\Service\AgendaSuggester;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Which agenda an event belongs to, and when the honest answer is a question (MAG-150).
 *
 * A kernel test rather than a unit one: two of the rules under test — a cancelled
 * occurrence is not a habit, and an evening booked for 2099 is not one either — live in
 * the query, so mocking the repository would assert the scoring against a world the
 * database would never hand over.
 *
 * Scores are asserted as numbers where the decision alone cannot show the rule, which is
 * also how the fixture's user isolation is checked: the neighbour owns an agenda with the
 * same name and an event with the same title, and every number below would come out
 * differently if either leaked in.
 */
class AgendaSuggesterTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('AgendaSuggesterTest.yaml');
    }

    private function suggester(): AgendaSuggester
    {
        return self::getContainer()->get(AgendaSuggester::class);
    }

    private function user(string $ref = 'test_user'): User
    {
        $user = $this->getFixture($ref);
        self::assertInstanceOf(User::class, $user);

        return $user;
    }

    /** @param list<AgendaCandidate> $candidates */
    private static function names(array $candidates): array
    {
        return array_map(static fn (AgendaCandidate $c) => $c->agenda->getName(), $candidates);
    }

    public function testAnAgendaNamedByTheEventItselfIsTheAnswer(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Concert de Stromae');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertInstanceOf(Agenda::class, $choice->agenda);
        self::assertSame('Concerts', $choice->agenda->getName());
        self::assertSame(100.0, $choice->candidates[0]->score);
        self::assertStringContainsString('concert', (string) $choice->reason);
    }

    public function testTheWholeNameInTheEventIsWorthMoreThanOneOfItsWords(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Concerts de la saison');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Concerts', $choice->agenda?->getName());
        self::assertSame(140.0, $choice->candidates[0]->score);
    }

    public function testAnAgendasDescriptionIsASignalOfItsOwn(): void
    {
        // Nothing names an agenda and nothing like this was ever filed; « client » is only
        // in « Boulot »'s description, and 30 is enough when nothing competes with it.
        $choice = $this->suggester()->suggest($this->user(), 'Dossier client');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame(30.0, $choice->candidates[0]->score);
        self::assertStringContainsString('its description mentions "client"', (string) $choice->reason);
    }

    public function testTheAgendaPastAppointmentsWentToIsTheAnswer(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Rendez-vous avec Paul');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        // 40 for the word « Paul », all of whose events are here, plus 60 for the title
        // already used. The neighbour's identical event would have made it 70.
        self::assertSame(100.0, $choice->candidates[0]->score);
    }

    public function testAWordSpreadOverEveryAgendaDoesNotOutweighTheHabitOneHolds(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Déjeuner avec Alex');

        // « Déjeuner » is filed in three agendas, so it hands each a third of its weight;
        // « Alex » is only ever lunched with in one.
        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Perso', $choice->agenda?->getName());
    }

    public function testAPlaceTheUserAlreadyGoesToPointsAtAnAgenda(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Impro', location: 'UBU');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Famille', $choice->agenda?->getName());
        self::assertSame(25.0, $choice->candidates[0]->score);
    }

    public function testTwoAgendasFittingEquallyWellAreBothProposedAndNoneIsChosen(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Déjeuner avec Camille');

        self::assertSame(AgendaChoiceKind::Ambiguous, $choice->kind);
        self::assertNull($choice->agenda);
        // « Perso » lunches too, but only a third as convincingly: it is not worth asking about.
        self::assertSame(['Boulot', 'Famille'], self::names($choice->candidates));
        self::assertStringContainsString('Boulot (id ', $choice->candidates[0]->describe());
        self::assertStringContainsString('a past event here has the same title', $choice->candidates[0]->describe());
    }

    public function testTheQuestionNeverListsMoreThanThreeAgendas(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Atelier poterie');

        self::assertSame(AgendaChoiceKind::Ambiguous, $choice->kind);
        self::assertCount(3, $choice->candidates);
    }

    public function testACancelledOccurrenceIsNotAHabit(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Yoga');

        // The only « Yoga » the user ever had was refused, so it points nowhere.
        self::assertSame(AgendaChoiceKind::Fallback, $choice->kind);
        self::assertSame('Perso', $choice->agenda?->getName());
    }

    public function testAnEventBookedPastTheHorizonIsNotAHabitEither(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Concert des Mouettes');

        // « Famille » holds a 2099 concert. Were it read as history it would compete with
        // « Concerts » and the answer would be a question instead of a choice.
        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Concerts', $choice->agenda?->getName());
        self::assertSame(['Concerts'], self::names($choice->candidates));
    }

    public function testNothingPointingAnywhereFallsBackToTheDefaultAgenda(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Plomberie');

        self::assertSame(AgendaChoiceKind::Fallback, $choice->kind);
        self::assertSame('Perso', $choice->agenda?->getName());
        self::assertSame([], $choice->candidates);
    }

    /** @return iterable<string, array{string}> */
    public static function pluralsOfGenericWords(): iterable
    {
        yield 'long enough to be singularised' => ['Réunions'];
        // Short ones are not — `singular()` leaves a four or five letter word alone — so the
        // lists have to be read against the de-pluralised form as well as against the word.
        // The fixture holds « RDVs du mois » and « Trucs à faire » so that these two have an
        // agenda to point at if the plural survives.
        yield 'too short to be singularised' => ['RDVs'];
        yield 'shorter still' => ['Trucs'];
    }

    #[DataProvider('pluralsOfGenericWords')]
    public function testThePluralOfAGenericWordIsDroppedToo(string $title): void
    {
        // These name a kind of entry, not a subject — kept, they would point at whichever
        // agenda happens to hold the most of them.
        $choice = $this->suggester()->suggest($this->user(), $title);

        self::assertSame(AgendaChoiceKind::Fallback, $choice->kind);
        self::assertSame('Perso', $choice->agenda?->getName());
    }

    public function testWithoutADefaultAgendaItAsks(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement('UPDATE agenda SET is_default = false');
        $em->clear();

        $choice = $this->suggester()->suggest($this->user(), 'Plomberie');

        self::assertSame(AgendaChoiceKind::Ask, $choice->kind);
        self::assertNull($choice->agenda);
    }

    public function testWithNoAgendaAtAllItAsks(): void
    {
        $choice = $this->suggester()->suggest($this->user('agendaless_user'), 'Concert de Stromae');

        self::assertSame(AgendaChoiceKind::Ask, $choice->kind);
    }

    public function testWhatTheUserSaidAboutTheAgendaIsReadEvenShortenedOrMistyped(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Point client', spoken: 'boulo');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame(200.0, $choice->candidates[0]->score);
        self::assertSame(['Boulot'], self::names($choice->candidates));
    }

    public function testWhatTheUserSaidCanMatchAnAgendasDescriptionWhenNoNameAnswers(): void
    {
        $choice = $this->suggester()->suggest($this->user(), 'Formation interne', spoken: 'collègues');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame(120.0, $choice->candidates[0]->score);
    }

    public function testAnAgendaCalledItBeatsOneMerelyTalkingAboutIt(): void
    {
        // « Perso » is described as « tout ce qui n'est pas le boulot ». Scoring names and
        // descriptions together would put it 200 against 120 behind « Boulot » — inside the
        // dominance band — and the answer would be a question with an obvious answer.
        $choice = $this->suggester()->suggest($this->user(), 'Point client', spoken: 'boulot');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame(['Boulot'], self::names($choice->candidates));
    }

    public function testAShortenedReferenceReachesANameButNeverTheInsideOfADescription(): void
    {
        // « sport » is written inside « Déplacements et transports », which describes
        // « Voyages ». Reaching in there would file the event in an agenda the user never
        // mentioned; MAG-230 answered that no agenda carries that name, and so does this.
        $choice = $this->suggester()->suggest($this->user(), 'Footing', spoken: 'sport');

        self::assertSame(AgendaChoiceKind::Ask, $choice->kind);
        self::assertSame([], $choice->candidates);
    }

    public function testASpokenReferenceThatPointsNowhereAsksRatherThanFallingBack(): void
    {
        // The user did say something about the agenda. Booking in « Perso » instead would
        // be the behaviour this ticket exists to remove.
        $choice = $this->suggester()->suggest($this->user(), 'Plomberie', spoken: 'Théâtre');

        self::assertSame(AgendaChoiceKind::Ask, $choice->kind);
        self::assertNull($choice->agenda);
    }

    public function testWhatTheUserSaidIsNeverOutvotedByTheEventsOwnWords(): void
    {
        // « Concerts » wins this title on its own. The user named an agenda they do not
        // have, and filing it in « Concerts » anyway would be contradicting them.
        $choice = $this->suggester()->suggest($this->user(), 'Concert de Stromae', spoken: 'Théâtre');

        self::assertSame(AgendaChoiceKind::Ask, $choice->kind);
        self::assertSame([], $choice->candidates);
    }

    public function testTwoAgendasTheSpokenNameFitsRaiseTheQuestionToo(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement(
            "UPDATE agenda SET name = 'Boulot client' WHERE name = 'Famille'",
        );
        $em->clear();

        $choice = $this->suggester()->suggest($this->user(), 'Formation interne', spoken: 'boulot');

        self::assertSame(AgendaChoiceKind::Ambiguous, $choice->kind);
        self::assertSame(['Boulot', 'Boulot client'], self::names($choice->candidates));
    }

    public function testAWordCarriedEvenlyByEveryAgendaDecidesNothing(): void
    {
        // The neighbour cycles once in each of their two agendas. Its share would hand each
        // of them half of the weight — over the floor, so the answer would be a two-way
        // question — where the ticket asks for the default agenda when nothing orients it.
        $choice = $this->suggester()->suggest($this->user('other_user'), 'Vélo du dimanche');

        self::assertSame(AgendaChoiceKind::Fallback, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame([], $choice->candidates);
    }

    public function testAHabitThatLeansIsReadEvenWhenItReachesEveryAgenda(): void
    {
        // This user has two agendas, so « reaches every agenda » and « three times out of
        // four at work » are the same condition. Dropping it on the first reading would put
        // the event back in the default agenda for anyone with two agendas — which is the
        // second acceptance criterion of the ticket, and the common shape of an account.
        $choice = $this->suggester()->suggest($this->user('two_agenda_user'), 'Brunch avec Léa');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind);
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame(30.0, $choice->candidates[0]->score);
    }

    public function testTheReasonsAreOrderedByWhatEachSignalScored(): void
    {
        // Only the first two reasons are ever said, so their order decides what the user
        // reads. The place is read before the words but scores less than them — 25 against
        // 40 — and in the order the signals happen to be read it would push both words out.
        $choice = $this->suggester()->suggest($this->user(), 'Spectacle de rue', location: 'UBU');

        $famille = $choice->candidates[0];
        self::assertSame('Famille', $famille->agenda->getName());
        self::assertSame(
            ['a past event here has the same title', 'past events here mention "spectacle"'],
            array_slice($famille->reasons, 0, 2),
        );
        self::assertContains('past events here are at "ubu"', $famille->reasons);
    }

    public function testAnotherUsersAgendasAndEventsAreNeverScored(): void
    {
        // The caller owns « Concerts » and the appointments with Paul; this user owns
        // neither, and nothing of theirs may reach either answer.
        $concert = $this->suggester()->suggest($this->user('other_user'), 'Concert de Stromae');
        $paul = $this->suggester()->suggest($this->user('other_user'), 'Rendez-vous avec Paul');

        self::assertSame(AgendaChoiceKind::Fallback, $concert->kind);
        self::assertSame('other@example.com', $concert->agenda?->getUser()->getEmail());
        self::assertSame('other@example.com', $paul->agenda?->getUser()->getEmail());
    }
}
