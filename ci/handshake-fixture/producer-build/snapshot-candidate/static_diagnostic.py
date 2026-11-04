"""Finite static-phase stop reporting. Never return exception text or filesystem paths."""
from host_contract import Stop,HostPathStop,PATH_CONTEXTS,PATH_CATEGORIES
HOST_REASONS=frozenset({
 'ELF_SIZE','ELF_BOUNDS','ELF_FORMAT','ELF_HEADER','ELF_SEGMENT','ELF_DUP_SEGMENT','ELF_STRING','HOST_INTERPRETER','DYNAMIC_CAP','NEEDED_CAP','DYNAMIC_DUPLICATE','NODEFLIB_UNSUPPORTED','DYNAMIC_UNTERMINATED','STRTAB','STRTAB_MAPPING','NEEDED_NAME','HOST_PATH','HOST_START_SET','HOST_ROW','HOST_ALIAS','HOST_SIZE','HOST_CHANGED','RPATH_INHERITANCE_UNSUPPORTED','HOST_RUNPATH','HOST_MISSING','HOST_AMBIGUOUS','HOST_OUTSIDE_ROOTS','HOST_NODE_CAP','HOST_BYTE_CAP','HOST_TOOL_INTERPRETER_REQUIRED','HOST_EDGE_CAP',
 'HOST_BINDING_DEADLINE','HOST_ALIAS_PATH','HOST_ALIAS_ESCAPE','HOST_ALIAS_CHANGED','HOST_ALIAS_CAP','HOST_ALIAS_CYCLE','HOST_COMPONENT_CHANGED','HOST_TERMINAL_TYPE','HOST_BINDING_BYTE_CAP','HOST_TERMINAL_CHANGED','HOST_BINDING_CHANGED',
 'STATIC_DEADLINE_RESERVE','HOST_ROOT_CHANGED','HOST_LOOKUP_CAP','HOST_ABSENCE_CHANGED','HOST_CONFIG_CAP','HOST_CONFIG_CHANGED','HOST_PRELOAD','SEALED_TOOL_BINDING'})
PHASES=frozenset({'ENTER','CONFIG_PRE','TOOLS','CLOSURE','CONFIG_POST','POST_BINDINGS','COMPLETE'})
class StaticPhaseStop(Stop):
 def __init__(self,error,phase,session=None):
  reason=error.args[0] if isinstance(error,Stop) and len(error.args)==1 and error.args[0] in HOST_REASONS else 'STATIC_UNCLASSIFIED_STOP'
  super().__init__(reason)
  self.checkpoint={'status':'STATIC_STOP_PARTIAL_NOT_QUALIFIED','phase':phase if phase in PHASES else 'ENTER','reason':reason}
  if isinstance(error,HostPathStop) and error.path_category.get('context') in PATH_CONTEXTS and error.path_category.get('category') in PATH_CATEGORIES:
   self.checkpoint['path_category']=dict(error.path_category)
  if session is not None:
   self.checkpoint.update({'lookup_attempts':session.attempts,'unique_requested_paths':len(session.records),'unique_observed_bytes':session.bytes,'phase_resources':list(session.phases)})
