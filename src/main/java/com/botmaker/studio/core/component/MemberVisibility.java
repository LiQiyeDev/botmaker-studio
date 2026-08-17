package com.botmaker.studio.core.component;

import com.botmaker.studio.core.component.BlockComponent.Visibility;
import com.botmaker.studio.project.LockResolver;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;

/**
 * Who a <em>class member</em> is drawn for — {@link BlockComponent}'s rule applied one level up, to the enum,
 * field and method declarations a file is made of.
 *
 * <p>The component schema filters fields <em>inside</em> a block; this filters whole members out of the tree
 * before a block is even built, which is what the canvas needs: an activity stub's {@code Outcome} enum, its
 * {@code INSTANCE} static and its {@code isEnabled()} wiring are three separate blocks, and dimming them was
 * never the ask — a person running someone else's bot has no use for them at all.
 *
 * <p><b>Nor, it turns out, does the author.</b> Those same three members were shown to the editor on the
 * grounds that they are the author's responsibility, which confused responsibility with editability: every one
 * of them is written from somewhere else and any edit made here is silently reverted on the next save. So in a
 * file the Studio scaffolds, they are drawn for {@link Visibility#NOBODY} and an activity is what its author
 * came to see — {@code run()} and the methods they wrote themselves.
 *
 * <p><b>Nothing here re-derives a lock.</b> Every rule below is either a {@link LockResolver} verdict or a
 * structural fact about the member (is it static?), so the rules that decide what may be edited stay in one
 * place and this only decides what is worth showing. The single non-lock rule — a static field in a
 * scaffold-managed file — exists because those fields are perfectly editable by their author and still mean
 * nothing to a reader: {@code INSTANCE} is wiring the entry point binds, and {@code Popups.POPUPS} is the
 * author's template list.
 */
public final class MemberVisibility {

    /** The enum the flow dialog owns. Named here only to catch it in the hooks, where it is not lock-flagged. */
    private static final String OUTCOME_ENUM = "Outcome";

    private MemberVisibility() {}

    /**
     * The audience {@code member} is drawn for. {@link Visibility#EVERYONE} unless the member is scaffolding —
     * and, as everywhere in this model, a null resolver ("we don't know what project this is") shows everything.
     *
     * <p>Two kinds of scaffolding, and the difference is where the file it sits in came from. Inside a file
     * <b>the Studio scaffolds into the user's own tree</b> — an activity stub, a {@code Bot.start} hook — the
     * generated members are {@link Visibility#NOBODY}: nobody edits them here, because each is already edited
     * somewhere else (the flow canvas owns the {@code Outcome}, the flow dialog's checkbox owns
     * {@code isEnabled()}) or is not editable at all ({@code INSTANCE}). Drawing them gave the author controls
     * that did nothing, in the file they spend all their time in. Inside a file <b>the Studio owns
     * outright</b> — {@code FlowDriver}, {@code ActivityRegistry} — the members stay
     * {@link Visibility#EDITOR_ONLY}: the whole file is generated, so hiding its members would leave an empty
     * class on screen, and the author may legitimately want to read what BotMaker wrote.
     */
    public static Visibility of(LockResolver resolver, BodyDeclaration member) {
        if (resolver == null || member == null) return Visibility.EVERYONE;

        // Whether this file is the user's with BotMaker members in it, or BotMaker's outright.
        boolean scaffoldManaged = resolver.isScaffoldManaged();

        if (member instanceof MethodDeclaration method) {
            // A method whose body the user may not change is generated wiring: an activity's isEnabled(), or
            // anything at all inside a file the Studio owns outright.
            if (resolver.bodyEditable(method)) return Visibility.EVERYONE;
            return scaffoldManaged ? Visibility.NOBODY : Visibility.EDITOR_ONLY;
        }
        if (member instanceof EnumDeclaration enumDecl) {
            if (!resolver.signatureEditable(enumDecl)) {
                return scaffoldManaged ? Visibility.NOBODY : Visibility.EDITOR_ONLY;
            }
            // GoHome and Popups carry an Outcome enum too, but they are called directly rather than routed on,
            // so GeneratedMembers doesn't lock theirs. It is still written by ActivityStubSync, so editing it
            // here would be undone on the next save — hide it from its author as well as from a reader.
            return scaffoldManaged && isNamed(enumDecl, OUTCOME_ENUM)
                    ? Visibility.NOBODY : Visibility.EVERYONE;
        }
        if (member instanceof FieldDeclaration field) {
            // The locked one is INSTANCE, and it is nobody's: the registry and the entry point bind through it.
            if (!resolver.signatureEditable(field)) {
                return scaffoldManaged ? Visibility.NOBODY : Visibility.EDITOR_ONLY;
            }
            // Any other static in a scaffolded file is the author's own — Popups.POPUPS is their template list,
            // theirs to edit — and only noise to someone reading the bot.
            return scaffoldManaged && Modifier.isStatic(field.getModifiers())
                    ? Visibility.EDITOR_ONLY : Visibility.EVERYONE;
        }
        return Visibility.EVERYONE;
    }

    /** Whether {@code member} is drawn for {@code audience} at all. */
    public static boolean isVisible(LockResolver resolver, BodyDeclaration member, Audience audience) {
        return ComponentResolver.isVisibleTo(of(resolver, member), audience);
    }

    private static boolean isNamed(EnumDeclaration enumDecl, String name) {
        return enumDecl.getName() != null && name.equals(enumDecl.getName().getIdentifier());
    }
}
