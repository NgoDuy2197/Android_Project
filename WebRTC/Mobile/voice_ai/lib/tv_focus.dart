import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Android TV / D-pad support: device detection, a global "very visible focus"
/// theme layer, a focusable wrapper for custom tappables, a remote-friendly
/// text field, and a D-pad scrollable region for read-only lists.
class TvMode {
  TvMode._();

  /// True on Android TV / leanback / non-touch devices (asked natively).
  static bool isTv = false;

  static Future<void> init(MethodChannel channel) async {
    try {
      isTv = await channel.invokeMethod<bool>('isTv') ?? false;
    } catch (_) {
      isTv = false;
    }
    // On a TV the focus ring must be visible from the very first frame. On a
    // phone keep `automatic`: it still flips to the traditional (visible)
    // mode as soon as any hardware key / D-pad is pressed.
    FocusManager.instance.highlightStrategy = isTv
        ? FocusHighlightStrategy.alwaysTraditional
        : FocusHighlightStrategy.automatic;
    _hookInput();
  }

  /// True after a remote / D-pad / keyboard key, false again after a touch.
  /// The focus ring follows this instead of relying only on
  /// FocusManager.highlightMode, which can stay "touch" on some devices even
  /// while the remote is being used (seen on a Realme phone).
  static final ValueNotifier<bool> keyNav = ValueNotifier<bool>(false);
  static bool _hooked = false;

  static void _hookInput() {
    if (_hooked) return;
    _hooked = true;
    HardwareKeyboard.instance.addHandler((event) {
      if (event is KeyDownEvent && !keyNav.value) keyNav.value = true;
      return false; // observe only
    });
    GestureBinding.instance.pointerRouter.addGlobalRoute((event) {
      if (event is PointerDownEvent &&
          event.kind == PointerDeviceKind.touch &&
          keyNav.value) {
        keyNav.value = false;
      }
    });
  }

  /// True while focus highlights should be drawn (TV, or a key was pressed).
  static bool get showFocus =>
      isTv ||
      keyNav.value ||
      FocusManager.instance.highlightMode == FocusHighlightMode.traditional;
}

/// High-contrast focus ring colour for the given theme.
Color tvRingColor(ThemeData t) => t.brightness == Brightness.dark
    ? const Color(0xFFFFD54F) // bright amber on dark backgrounds
    : const Color(0xFFFF6D00); // deep orange on light / blue / pink themes

const double tvRingWidth = 3.5;

bool _focused(Set<WidgetState> s) => s.contains(WidgetState.focused);

/// Adds a thick ring + tinted overlay to a button style when focused,
/// preserving every other state's original value.
ButtonStyle _focusStyle(ButtonStyle? style, Color ring, {bool lift = false}) {
  final s = style ?? const ButtonStyle();
  final side = s.side;
  final overlay = s.overlayColor;
  final elevation = s.elevation;
  return s.copyWith(
    side: WidgetStateProperty.resolveWith(
      (st) => _focused(st)
          ? BorderSide(color: ring, width: tvRingWidth)
          : side?.resolve(st),
    ),
    overlayColor: WidgetStateProperty.resolveWith(
      (st) =>
          _focused(st) ? ring.withValues(alpha: 0.30) : overlay?.resolve(st),
    ),
    elevation: lift
        ? WidgetStateProperty.resolveWith(
            (st) => _focused(st) ? 8 : elevation?.resolve(st),
          )
        : elevation,
  );
}

/// Layers strong focus visuals over any app theme (buttons, icon buttons,
/// list tiles, switches, chips, dropdowns, text fields, dialog actions).
ThemeData withTvFocus(ThemeData t) {
  final ring = tvRingColor(t);
  final ringSide = BorderSide(color: ring, width: tvRingWidth);
  return t.copyWith(
    focusColor: ring.withValues(alpha: 0.35),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: _focusStyle(t.elevatedButtonTheme.style, ring, lift: true),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: _focusStyle(t.filledButtonTheme.style, ring, lift: true),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: _focusStyle(t.outlinedButtonTheme.style, ring),
    ),
    textButtonTheme: TextButtonThemeData(
      style: _focusStyle(t.textButtonTheme.style, ring),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: _focusStyle(t.iconButtonTheme.style, ring),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: _focusStyle(t.segmentedButtonTheme.style, ring),
      selectedIcon: t.segmentedButtonTheme.selectedIcon,
    ),
    switchTheme: t.switchTheme.copyWith(
      overlayColor: WidgetStateProperty.resolveWith(
        (st) => _focused(st)
            ? ring.withValues(alpha: 0.45)
            : t.switchTheme.overlayColor?.resolve(st),
      ),
      trackOutlineColor: WidgetStateProperty.resolveWith(
        (st) =>
            _focused(st) ? ring : t.switchTheme.trackOutlineColor?.resolve(st),
      ),
      trackOutlineWidth: WidgetStateProperty.resolveWith(
        (st) => _focused(st)
            ? tvRingWidth
            : t.switchTheme.trackOutlineWidth?.resolve(st),
      ),
    ),
    checkboxTheme: t.checkboxTheme.copyWith(
      overlayColor: WidgetStateProperty.resolveWith(
        (st) => _focused(st)
            ? ring.withValues(alpha: 0.45)
            : t.checkboxTheme.overlayColor?.resolve(st),
      ),
    ),
    radioTheme: t.radioTheme.copyWith(
      overlayColor: WidgetStateProperty.resolveWith(
        (st) => _focused(st)
            ? ring.withValues(alpha: 0.45)
            : t.radioTheme.overlayColor?.resolve(st),
      ),
    ),
    chipTheme: t.chipTheme.copyWith(
      side: WidgetStateBorderSide.resolveWith(
        (st) => _focused(st) ? ringSide : t.chipTheme.side,
      ),
    ),
    sliderTheme: t.sliderTheme.copyWith(
      overlayColor: ring.withValues(alpha: 0.35),
    ),
    inputDecorationTheme: t.inputDecorationTheme.copyWith(
      focusedBorder: OutlineInputBorder(
        borderRadius: const BorderRadius.all(Radius.circular(6)),
        borderSide: ringSide,
      ),
    ),
  );
}

/// Makes any custom tappable (GestureDetector / InkWell card, painted button…)
/// reachable with the D-pad: it takes focus, draws a rounded high-contrast
/// ring + glow and grows slightly while focused, and fires [onTap] on
/// Enter / Select (D-pad centre) / Space / GameButtonA.
class TvFocusable extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final BorderRadius borderRadius;
  final bool autofocus;
  final FocusNode? focusNode;

  /// Wrap [child] in a GestureDetector for touch. Set false when the child
  /// already handles taps itself (only key activation is added then).
  final bool handlePointer;
  final double focusedScale;

  const TvFocusable({
    super.key,
    required this.child,
    required this.onTap,
    this.borderRadius = const BorderRadius.all(Radius.circular(12)),
    this.autofocus = false,
    this.focusNode,
    this.handlePointer = true,
    this.focusedScale = 1.05,
  });

  @override
  State<TvFocusable> createState() => _TvFocusableState();
}

class _TvFocusableState extends State<TvFocusable> {
  bool _focused = false;

  @override
  void initState() {
    super.initState();
    TvMode.keyNav.addListener(_refresh);
  }

  @override
  void dispose() {
    TvMode.keyNav.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  static const _shortcuts = <ShortcutActivator, Intent>{
    SingleActivator(LogicalKeyboardKey.select): ActivateIntent(),
    SingleActivator(LogicalKeyboardKey.enter): ActivateIntent(),
    SingleActivator(LogicalKeyboardKey.numpadEnter): ActivateIntent(),
    SingleActivator(LogicalKeyboardKey.space): ActivateIntent(),
    SingleActivator(LogicalKeyboardKey.gameButtonA): ActivateIntent(),
  };

  @override
  Widget build(BuildContext context) {
    final ring = tvRingColor(Theme.of(context));
    final enabled = widget.onTap != null;
    final highlight = _focused && TvMode.showFocus;
    Widget child = widget.child;
    if (widget.handlePointer) {
      child = GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: child,
      );
    }
    return FocusableActionDetector(
      enabled: enabled,
      autofocus: widget.autofocus,
      focusNode: widget.focusNode,
      shortcuts: _shortcuts,
      actions: <Type, Action<Intent>>{
        ActivateIntent: CallbackAction<ActivateIntent>(
          onInvoke: (_) {
            widget.onTap?.call();
            return null;
          },
        ),
      },
      onFocusChange: (f) {
        if (mounted && f != _focused) setState(() => _focused = f);
      },
      child: AnimatedScale(
        scale: highlight ? widget.focusedScale : 1.0,
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          decoration: BoxDecoration(
            borderRadius: widget.borderRadius,
            boxShadow: highlight
                ? [
                    BoxShadow(
                      color: ring.withValues(alpha: 0.65),
                      blurRadius: 16,
                      spreadRadius: 2,
                    ),
                  ]
                : const [],
          ),
          foregroundDecoration: BoxDecoration(
            borderRadius: widget.borderRadius,
            border: Border.all(
              color: highlight ? ring : Colors.transparent,
              width: tvRingWidth,
            ),
          ),
          child: child,
        ),
      ),
    );
  }
}

/// Keys that mean "go back / cancel" on remotes and keyboards.
bool _isBackKey(LogicalKeyboardKey k) =>
    k == LogicalKeyboardKey.goBack ||
    k == LogicalKeyboardKey.escape ||
    k == LogicalKeyboardKey.browserBack;

/// Keys that mean "OK / activate" on remotes and keyboards.
bool _isOkKey(LogicalKeyboardKey k) =>
    k == LogicalKeyboardKey.select ||
    k == LogicalKeyboardKey.enter ||
    k == LogicalKeyboardKey.numpadEnter ||
    k == LogicalKeyboardKey.gameButtonA;

TraversalDirection? _arrowDir(LogicalKeyboardKey k) {
  if (k == LogicalKeyboardKey.arrowUp) return TraversalDirection.up;
  if (k == LogicalKeyboardKey.arrowDown) return TraversalDirection.down;
  if (k == LogicalKeyboardKey.arrowLeft) return TraversalDirection.left;
  if (k == LogicalKeyboardKey.arrowRight) return TraversalDirection.right;
  return null;
}

/// Remote-friendly text field.
///
/// * **Navigation mode** (default): read-only, no keyboard, no cursor; the
///   field is highlighted like any other focusable and the D-pad arrows move
///   focus to its neighbours.
/// * **Edit mode**: entered with Enter / Select / OK (or a touch tap on a
///   phone). The keyboard opens normally.
/// * Done / Enter (single-line), Back / Escape, or the keyboard being
///   dismissed leaves edit mode: the value is committed ([onCommit]), the
///   keyboard is hidden and focus stays on the field. Back while editing
///   never pops the route.
class TvTextField extends StatefulWidget {
  final TextEditingController controller;
  final InputDecoration decoration;
  final int? maxLines;
  final int? minLines;
  final TextInputType? keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final TextInputAction? textInputAction;
  final bool autocorrect;
  final bool autofocus;

  /// Start directly in edit mode (dialogs whose only purpose is typing).
  final bool startEditing;
  final FocusNode? focusNode;

  /// Called on Done / Enter (keyboard "submit" action).
  final ValueChanged<String>? onSubmitted;

  /// Called whenever edit mode is left (Done, Back, keyboard hidden, blur).
  final ValueChanged<String>? onCommit;

  const TvTextField({
    super.key,
    required this.controller,
    this.decoration = const InputDecoration(),
    this.maxLines = 1,
    this.minLines,
    this.keyboardType,
    this.inputFormatters,
    this.textInputAction,
    this.autocorrect = false,
    this.autofocus = false,
    this.startEditing = false,
    this.focusNode,
    this.onSubmitted,
    this.onCommit,
  });

  @override
  State<TvTextField> createState() => _TvTextFieldState();
}

class _TvTextFieldState extends State<TvTextField> with WidgetsBindingObserver {
  FocusNode? _ownNode;
  FocusNode get _node => widget.focusNode ?? (_ownNode ??= FocusNode());
  late bool _editing = widget.startEditing;
  bool _keyboardSeen = false;

  @override
  void initState() {
    super.initState();
    _node.onKeyEvent = _onKey;
    _node.addListener(_onFocusChange);
    WidgetsBinding.instance.addObserver(this);
    FocusManager.instance.addHighlightModeListener(_onHighlightMode);
    TvMode.keyNav.addListener(_onKeyNav);
  }

  void _onKeyNav() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    FocusManager.instance.removeHighlightModeListener(_onHighlightMode);
    TvMode.keyNav.removeListener(_onKeyNav);
    WidgetsBinding.instance.removeObserver(this);
    _node.removeListener(_onFocusChange);
    if (_node.onKeyEvent == _onKey) _node.onKeyEvent = null;
    _ownNode?.dispose();
    super.dispose();
  }

  void _onHighlightMode(FocusHighlightMode _) {
    if (mounted) setState(() {});
  }

  void _onFocusChange() {
    if (!mounted) return;
    if (!_node.hasFocus && _editing) {
      _editing = false;
      _keyboardSeen = false;
      widget.onCommit?.call(widget.controller.text);
    }
    setState(() {});
  }

  /// The soft keyboard was dismissed (e.g. Back hid it) → leave edit mode.
  @override
  void didChangeMetrics() {
    if (!_editing || !mounted) return;
    final view = View.maybeOf(context);
    if (view == null) return;
    final inset = view.viewInsets.bottom;
    if (inset > 0) {
      _keyboardSeen = true;
    } else if (_keyboardSeen) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _exitEdit());
    }
  }

  void _enterEdit() {
    if (_editing) return;
    setState(() {
      _editing = true;
      _keyboardSeen = false;
    });
    if (!_node.hasFocus) _node.requestFocus();
    final len = widget.controller.text.length;
    widget.controller.selection = TextSelection.collapsed(offset: len);
    // EditableText opens the input connection itself once readOnly flips
    // off while focused; nudge the keyboard in case the IME stayed hidden.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _editing && _node.hasFocus) {
        SystemChannels.textInput.invokeMethod<void>('TextInput.show');
      }
    });
  }

  void _exitEdit() {
    if (!_editing || !mounted) return;
    setState(() {
      _editing = false;
      _keyboardSeen = false;
    });
    SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    if (!_node.hasFocus) _node.requestFocus();
    widget.onCommit?.call(widget.controller.text);
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent e) {
    final k = e.logicalKey;
    final down = e is! KeyUpEvent;
    if (_editing) {
      if (_isBackKey(k)) {
        if (e is KeyDownEvent) _exitEdit();
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored;
    }
    if (_isOkKey(k)) {
      if (e is KeyDownEvent) _enterEdit();
      return KeyEventResult.handled;
    }
    final dir = _arrowDir(k);
    if (dir != null) {
      if (down) {
        final moved = node.focusInDirection(dir);
        if (!moved) {
          if (dir == TraversalDirection.down) node.nextFocus();
          if (dir == TraversalDirection.up) node.previousFocus();
        }
      }
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ring = tvRingColor(theme);
    final navFocused = !_editing && _node.hasFocus && TvMode.showFocus;
    final base = widget.decoration;
    final radius = const BorderRadius.all(Radius.circular(6));
    final InputBorder focusedBorder;
    if (_editing) {
      focusedBorder = OutlineInputBorder(
        borderRadius: radius,
        borderSide: BorderSide(color: theme.colorScheme.primary, width: 2.5),
      );
    } else if (navFocused) {
      focusedBorder = OutlineInputBorder(
        borderRadius: radius,
        borderSide: BorderSide(color: ring, width: tvRingWidth),
      );
    } else {
      // Focused but not editing and no key navigation in use (phone after
      // the keyboard closed): look like a normal, idle field.
      focusedBorder =
          base.enabledBorder ??
          base.border ??
          OutlineInputBorder(borderRadius: radius);
    }
    final decoration = base.copyWith(
      focusedBorder: focusedBorder,
      filled: navFocused ? true : base.filled,
      fillColor: navFocused ? ring.withValues(alpha: 0.12) : base.fillColor,
    );

    return PopScope(
      // Back while editing only leaves edit mode; it never pops the route.
      canPop: !_editing,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _editing) _exitEdit();
      },
      child: TextField(
        controller: widget.controller,
        focusNode: _node,
        autofocus: widget.autofocus,
        readOnly: !_editing,
        showCursor: _editing,
        enableInteractiveSelection: _editing,
        maxLines: widget.maxLines,
        minLines: widget.minLines,
        keyboardType: widget.keyboardType,
        inputFormatters: widget.inputFormatters,
        textInputAction: widget.textInputAction,
        autocorrect: widget.autocorrect,
        decoration: decoration,
        // Touch: a tap edits immediately (phone behaviour unchanged).
        onTap: _enterEdit,
        onEditingComplete: _exitEdit,
        onSubmitted: widget.onSubmitted,
      ),
    );
  }
}

/// A read-only scrollable region the D-pad can scroll: Up/Down scroll the
/// content while focused, and hand focus on (e.g. to the app bar) once the
/// edge is reached. Shows a ring while focused.
class TvKeyScroll extends StatefulWidget {
  final Widget Function(ScrollController controller) builder;
  final bool autofocus;
  final double step;
  const TvKeyScroll({
    super.key,
    required this.builder,
    this.autofocus = false,
    this.step = 160,
  });

  @override
  State<TvKeyScroll> createState() => _TvKeyScrollState();
}

class _TvKeyScrollState extends State<TvKeyScroll> {
  final _ctrl = ScrollController();
  bool _highlight = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent e) {
    if (e is KeyUpEvent || !_ctrl.hasClients) return KeyEventResult.ignored;
    final k = e.logicalKey;
    final p = _ctrl.position;
    double? target;
    if (k == LogicalKeyboardKey.arrowDown || k == LogicalKeyboardKey.pageDown) {
      if (p.pixels >= p.maxScrollExtent) return KeyEventResult.ignored;
      target =
          p.pixels +
          (k == LogicalKeyboardKey.pageDown
              ? p.viewportDimension * 0.9
              : widget.step);
    } else if (k == LogicalKeyboardKey.arrowUp ||
        k == LogicalKeyboardKey.pageUp) {
      if (p.pixels <= p.minScrollExtent) return KeyEventResult.ignored;
      target =
          p.pixels -
          (k == LogicalKeyboardKey.pageUp
              ? p.viewportDimension * 0.9
              : widget.step);
    }
    if (target == null) return KeyEventResult.ignored;
    _ctrl.animateTo(
      target.clamp(p.minScrollExtent, p.maxScrollExtent),
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOut,
    );
    return KeyEventResult.handled;
  }

  @override
  Widget build(BuildContext context) {
    final ring = tvRingColor(Theme.of(context));
    return Focus(
      autofocus: widget.autofocus,
      onKeyEvent: _onKey,
      onFocusChange: (f) => setState(() => _highlight = f && TvMode.showFocus),
      child: DecoratedBox(
        position: DecorationPosition.foreground,
        decoration: BoxDecoration(
          border: Border.all(
            color: _highlight ? ring : Colors.transparent,
            width: 3,
          ),
          borderRadius: const BorderRadius.all(Radius.circular(8)),
        ),
        child: widget.builder(_ctrl),
      ),
    );
  }
}

/// A FocusNode for widgets that consume Up/Down themselves (e.g. [Slider]):
/// on a remote, Up/Down move focus instead; Left/Right keep adjusting.
FocusNode tvVerticalEscapeNode() => FocusNode(
  onKeyEvent: (node, e) {
    if (e is KeyUpEvent) return KeyEventResult.ignored;
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.arrowUp) {
      if (!node.focusInDirection(TraversalDirection.up)) node.previousFocus();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowDown) {
      if (!node.focusInDirection(TraversalDirection.down)) node.nextFocus();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  },
);
